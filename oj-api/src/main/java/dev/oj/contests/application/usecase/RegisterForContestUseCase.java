package dev.oj.contests.application.usecase;

import dev.oj.contests.application.port.ContestRepository;
import dev.oj.contests.domain.Contest;
import dev.oj.contests.domain.ContestsException;
import dev.oj.platform.audit.AuditLog;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.security.RequiresRole;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Map;

/**
 * FR-CON-02 — đăng ký tham gia, <b>trước giờ bắt đầu</b>. Bước 5.5.
 *
 * <h2>Vì sao đóng đăng ký đúng lúc chuông reo</h2>
 * Cho đăng ký muộn nghe như lòng tốt, nhưng nó phá tính công bằng theo một cách khó thấy:
 * người vào sau đã biết đề (bạn bè đã kể), đã biết bảng xếp hạng, và biết nên làm đề nào
 * trước. Với một hệ thống mà thứ nó bán là sự công bằng, "vào muộn" không phải một tiện ích
 * mà là một lợi thế.
 *
 * <p>{@code registration_required = FALSE} là cách đúng để mở một kỳ thi cho ai cũng vào —
 * một quyết định của người tổ chức, đặt trước, không phải một ngoại lệ cấp lúc đang thi.
 */
@RequiresRole
@Service
public class RegisterForContestUseCase {

    private final CurrentUserProvider currentUser;
    private final ContestRepository contests;
    private final AuditLog auditLog;
    private final Clock clock;

    public RegisterForContestUseCase(CurrentUserProvider currentUser, ContestRepository contests,
                                     AuditLog auditLog, Clock clock) {
        this.currentUser = currentUser;
        this.contests = contests;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    public void thucHien(long contestId) {
        long userId = currentUser.current().id();
        Contest contest = contests.timTheoId(contestId)
                .orElseThrow(ContestsException::khongTimThay);

        if (!contest.chuaMo(clock.instant())) {
            throw ContestsException.dangKyDaDong();
        }
        contests.dangKy(contestId, userId, clock.instant());
        auditLog.ghi("CONTEST_REGISTERED", "contest", contestId,
                Map.of("slug", contest.slug()));
    }
}
