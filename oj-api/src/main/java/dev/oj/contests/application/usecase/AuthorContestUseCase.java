package dev.oj.contests.application.usecase;

import dev.oj.contests.application.port.ContestRepository;
import dev.oj.contests.domain.Contest;
import dev.oj.contests.domain.ContestFormats;
import dev.oj.contests.domain.ContestsException;
import dev.oj.platform.audit.AuditLog;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.security.RequiresRole;
import dev.oj.platform.security.Role;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * FR-CON-01 — tạo kỳ thi và gắn đề. Bước 5.2.
 *
 * <h2>★ Thêm đề vào kỳ thi là thao tác NHẠY CẢM nhất ở đây</h2>
 * Nó khoá đề khỏi công chúng (FR-CON-03) và cấm sửa đề (FR-PROB-11) ngay khi kỳ thi bắt đầu.
 * Làm nhầm trên một đề đang được dùng để luyện tập là đột nhiên nó biến mất với mọi người.
 *
 * <p>Vì thế mọi thao tác ở đây ghi {@code audit_log}: câu hỏi <i>"vì sao đề này biến mất"</i>
 * phải có câu trả lời truy được.
 */
@RequiresRole(Role.SETTER)
@Service
public class AuthorContestUseCase {

    /** Cùng khuôn với mã đề: xuất hiện trong URL và trong mọi liên kết đã chia sẻ. */
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]{1,63}$");

    /** Nhãn đề kiểu ICPC: A, B, ... hoặc AA. Ngắn vì nó là tiêu đề cột của bảng xếp hạng. */
    private static final Pattern NHAN = Pattern.compile("^[A-Z]{1,2}$");

    private final CurrentUserProvider currentUser;
    private final ContestRepository contests;
    private final AuditLog auditLog;
    private final Clock clock;

    public AuthorContestUseCase(CurrentUserProvider currentUser, ContestRepository contests,
                                AuditLog auditLog, Clock clock) {
        this.currentUser = currentUser;
        this.contests = contests;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    public long tao(Lenh lenh) {
        lenh.kiemTra(clock.instant());
        long id = contests.tao(new ContestRepository.ContestMoi(
                lenh.slug().trim(), lenh.title().trim(), lenh.format(),
                lenh.startsAt(), lenh.endsAt(), lenh.freezeAt(),
                lenh.penaltyMinutes(), lenh.registrationRequired(), lenh.revealAfterEnd(),
                currentUser.current().id()));
        auditLog.ghi("CONTEST_CREATED", "contest", id,
                Map.of("slug", lenh.slug().trim(), "format", lenh.format()));
        return id;
    }

    /**
     * Gắn một đề vào kỳ thi.
     *
     * <p><b>Không kiểm được rằng đề tồn tại từ đây</b>: luật ArchUnit 3 cấm {@code contests}
     * import {@code problems}... đúng ra là cho phép ({@code problems ◀── contests}), nhưng
     * chốt thật vẫn nên là khoá ngoại {@code contest_problems.problem_id → problems(id)}. Nó
     * không quên được, và nó không lệch được với dữ liệu.
     */
    public void themDe(long contestId, long problemId, String nhan, int thuTu, int diem) {
        Contest contest = contests.timTheoId(contestId)
                .orElseThrow(ContestsException::khongTimThay);
        if (nhan == null || !NHAN.matcher(nhan).matches()) {
            throw ContestsException.khongHopLe("contest.nhan_khong_hop_le",
                    "Nhãn đề phải là một hoặc hai chữ cái in hoa, ví dụ A hoặc AB.");
        }
        if (diem < 1) {
            throw ContestsException.khongHopLe("contest.diem_khong_hop_le",
                    "Điểm của đề phải là số dương.");
        }
        // Thêm đề sau khi kỳ thi đã bắt đầu là đổi luật giữa chừng: người vào sớm đã thấy một
        // bộ đề khác người vào muộn, và bảng xếp hạng so hai thứ không so được.
        if (!contest.chuaMo(clock.instant())) {
            throw ContestsException.khongHopLe("contest.da_bat_dau",
                    "Kỳ thi đã bắt đầu, không thêm đề được nữa.");
        }

        contests.themDe(contestId, problemId, nhan, thuTu, diem);
        auditLog.ghi("CONTEST_PROBLEM_ADDED", "contest", contestId,
                Map.of("problemId", problemId, "nhan", nhan));
    }

    /** @param freezeAt {@code null} = không đóng băng */
    public record Lenh(String slug, String title, String format,
                       Instant startsAt, Instant endsAt, Instant freezeAt,
                       int penaltyMinutes, boolean registrationRequired,
                       boolean revealAfterEnd) {

        void kiemTra(Instant bayGio) {
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
            // Tạo một kỳ thi đã bắt đầu trong quá khứ nghĩa là đăng ký đã đóng ngay lúc tạo,
            // và không ai vào được. Hầu như luôn là gõ nhầm múi giờ.
            if (!startsAt.isAfter(bayGio)) {
                throw ContestsException.khongHopLe("contest.bat_dau_trong_qua_khu",
                        "Giờ bắt đầu phải ở tương lai — nếu không thì đăng ký đóng ngay lúc tạo.");
            }
            if (penaltyMinutes < 0) {
                throw ContestsException.khongHopLe("contest.penalty_am",
                        "Số phút phạt không được âm.");
            }
        }
    }
}
