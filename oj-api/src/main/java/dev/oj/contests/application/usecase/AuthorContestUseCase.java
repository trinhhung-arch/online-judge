package dev.oj.contests.application.usecase;

import dev.oj.contests.application.port.ContestAuthoringRepository;
import dev.oj.contests.application.port.ContestRepository;
import dev.oj.contests.domain.Contest;
import dev.oj.contests.domain.ContestFormats;
import dev.oj.contests.domain.ContestsException;
import dev.oj.platform.audit.AuditLog;
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.security.CurrentUserProvider.CurrentUser;
import dev.oj.platform.security.RequiresRole;
import dev.oj.problems.application.usecase.AuthorProblemUseCase;
import dev.oj.platform.security.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.regex.Pattern;

@RequiresRole(Role.SETTER)
@Service
public class AuthorContestUseCase {

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]{1,63}$");

    private static final Pattern NHAN = Pattern.compile("^[A-Z]{1,2}$");

    private final CurrentUserProvider currentUser;
    private final ContestRepository contests;
    private final ContestAuthoringRepository soanKyThi;
    private final AuthorProblemUseCase authorProblem;
    private final AuditLog auditLog;
    private final AppProperties properties;
    private final Clock clock;

    public AuthorContestUseCase(CurrentUserProvider currentUser, ContestRepository contests,
                                ContestAuthoringRepository soanKyThi,
                                AuthorProblemUseCase authorProblem,
                                AuditLog auditLog, AppProperties properties, Clock clock) {
        this.currentUser = currentUser;
        this.contests = contests;
        this.soanKyThi = soanKyThi;
        this.authorProblem = authorProblem;
        this.auditLog = auditLog;
        this.properties = properties;
        this.clock = clock;
    }

    public long tao(Lenh lenh) {
        lenh.kiemTra(clock.instant(), properties.contest().penaltyMinutesMax());
        long id = contests.tao(new ContestRepository.ContestMoi(
                lenh.slug().trim(), lenh.title().trim(), lenh.format(),
                lenh.startsAt(), lenh.endsAt(), lenh.freezeAt(),
                lenh.penaltyMinutes(), lenh.registrationRequired(), lenh.revealAfterEnd(),
                currentUser.current().id()));
        auditLog.ghi("CONTEST_CREATED", "contest", id,
                Map.of("slug", lenh.slug().trim(), "format", lenh.format()));
        return id;
    }

    public void themDe(long contestId, long problemId, String nhan, int diem) {
        CurrentUser nguoiGoi = currentUser.current();
        kiemGanDe(contestId, nhan, diem, nguoiGoi);
        if (soanKyThi.deNamTrongKyThiChongGio(contestId, problemId, nguoiGoi.id(),
                nguoiGoi.isAdmin())) {
            throw ContestsException.khongHopLe("contest.de_trung_khung_gio",
                    "Đề này đang nằm trong một kỳ thi khác có khung giờ chồng lên kỳ thi này.");
        }
        soanKyThi.themDe(contestId, problemId, nhan, diem, nguoiGoi.id(), nguoiGoi.isAdmin());
        auditLog.ghi("CONTEST_PROBLEM_ADDED", "contest", contestId,
                Map.of("problemId", problemId, "nhan", nhan));
    }

    @Transactional
    public long soanDeRieng(long contestId, AuthorProblemUseCase.Command deMoi,
                            String nhan, int diem) {
        CurrentUser nguoiGoi = currentUser.current();
        kiemGanDe(contestId, nhan, diem, nguoiGoi);
        long problemId = authorProblem.tao(deMoi);
        soanKyThi.themDeSoanRieng(contestId, problemId, nhan, diem,
                nguoiGoi.id(), nguoiGoi.isAdmin());
        auditLog.ghi("CONTEST_PROBLEM_AUTHORED", "contest", contestId,
                Map.of("problemId", problemId, "nhan", nhan));
        return problemId;
    }

    public void goDeKhoiKyThi(long contestId, long problemId) {
        CurrentUser nguoiGoi = currentUser.current();
        Contest contest = soanKyThi.timDeSoan(contestId, nguoiGoi.id(), nguoiGoi.isAdmin())
                .orElseThrow(ContestsException::khongTimThay);
        if (!contest.chuaMo(clock.instant())) {
            throw ContestsException.khongHopLe("contest.da_bat_dau",
                    "Kỳ thi đã bắt đầu, không gỡ đề ra được nữa.");
        }
        if (!soanKyThi.goDe(contestId, problemId, nguoiGoi.id(), nguoiGoi.isAdmin())) {
            throw ContestsException.khongHopLe("contest.de_khong_trong_ky_thi",
                    "Đề này không nằm trong kỳ thi.");
        }
        auditLog.ghi("CONTEST_PROBLEM_REMOVED", "contest", contestId,
                Map.of("problemId", problemId));
    }

    private void kiemGanDe(long contestId, String nhan, int diem, CurrentUser nguoiGoi) {
        Contest contest = soanKyThi.timDeSoan(contestId, nguoiGoi.id(), nguoiGoi.isAdmin())
                .orElseThrow(ContestsException::khongTimThay);
        if (nhan == null || !NHAN.matcher(nhan).matches()) {
            throw ContestsException.khongHopLe("contest.nhan_khong_hop_le",
                    "Nhãn đề phải là một hoặc hai chữ cái in hoa, ví dụ A hoặc AB.");
        }
        if (diem < 1) {
            throw ContestsException.khongHopLe("contest.diem_khong_hop_le",
                    "Điểm của đề phải là số dương.");
        }
        if (!contest.chuaMo(clock.instant())) {
            throw ContestsException.khongHopLe("contest.da_bat_dau",
                    "Kỳ thi đã bắt đầu, không thêm đề được nữa.");
        }
    }

    public record Lenh(String slug, String title, String format,
                       Instant startsAt, Instant endsAt, Instant freezeAt,
                       int penaltyMinutes, boolean registrationRequired,
                       boolean revealAfterEnd) {

        void kiemTra(Instant bayGio, int phutPhatToiDa) {
            if (slug == null || !SLUG.matcher(slug.trim()).matches()) {
                throw ContestsException.khongHopLe("contest.slug_khong_hop_le",
                        "Mã kỳ thi phải dài 2–64 ký tự, chỉ gồm chữ thường, số và gạch ngang.");
            }
            if (title == null || title.isBlank() || title.length() > 200) {
                throw ContestsException.khongHopLe("contest.tieu_de_khong_hop_le",
                        "Tiêu đề phải có từ 1 đến 200 ký tự.");
            }
            if (format == null || !ContestFormats.maHopLe().contains(format)) {
                throw ContestsException.khongHopLe("contest.the_thuc_khong_hop_le",
                        "Thể thức phải là một trong: " + String.join(", ",
                                ContestFormats.maHopLe()) + ".");
            }
            if (startsAt == null || endsAt == null || !endsAt.isAfter(startsAt)) {
                throw ContestsException.khongHopLe("contest.khung_gio_khong_hop_le",
                        "Giờ kết thúc phải sau giờ bắt đầu.");
            }
            if (freezeAt != null && (!freezeAt.isAfter(startsAt) || freezeAt.isAfter(endsAt))) {
                throw ContestsException.khongHopLe("contest.gio_dong_bang_khong_hop_le",
                        "Giờ đóng băng phải nằm trong khung giờ thi.");
            }
            if (!startsAt.isAfter(bayGio)) {
                throw ContestsException.khongHopLe("contest.bat_dau_trong_qua_khu",
                        "Giờ bắt đầu phải ở tương lai — nếu không thì đăng ký đóng ngay lúc tạo.");
            }
            if (penaltyMinutes < 0) {
                throw ContestsException.khongHopLe("contest.penalty_am",
                        "Số phút phạt không được âm.");
            }
            if (penaltyMinutes > phutPhatToiDa) {
                throw ContestsException.khongHopLe("contest.penalty_qua_lon",
                        "Số phút phạt tối đa là " + phutPhatToiDa + ".");
            }
        }
    }
}
