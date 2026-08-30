package dev.oj.contests.application.usecase;

import dev.oj.contests.application.StandingsEventBus;
import dev.oj.contests.application.port.ContestRepository;
import dev.oj.contests.domain.Contest;
import dev.oj.contests.domain.ContestsException;
import dev.oj.platform.audit.AuditLog;
import dev.oj.platform.security.RequiresRole;
import dev.oj.platform.security.Role;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Map;

/**
 * FR-CON-05 và FR-CON-07 — công bố bảng xếp hạng đầy đủ. Bước 5.11.
 *
 * <h2>Ba thứ mở ra sau khi công bố, và chỉ một trong ba cần lớp này</h2>
 * <ul>
 *   <li><b>Đề mở ra ngoài</b> — tự động: {@code ContestWindowQuery.deBiKhoaBoiLichThi} chỉ
 *       khoá khi {@code ends_at > bây giờ}. Không cần ai bấm gì.</li>
 *   <li><b>AI review mở lại</b> — tự động: module {@code ai} (tuần 14–15) hỏi cùng một
 *       {@code ContestWindowQuery}, và nó trả lời "không còn contest đang chạy".</li>
 *   <li><b>Bảng xếp hạng đầy đủ</b> — <b>không</b> tự động, và đó là chủ ý.</li>
 * </ul>
 *
 * <h2>★ Vì sao bảng xếp hạng KHÔNG tự mở khi hết giờ</h2>
 * Đóng băng không hết hiệu lực khi chuông reo — nó kéo dài tới khi có người công bố. Đó là cả
 * điểm của nghi thức trao giải kiểu ICPC: bảng vẫn kín sau tiếng chuông, và được mở ra trước
 * mặt mọi người. Tự mở lúc {@code ends_at} là xoá mất khoảnh khắc ấy, và không có cách nào
 * lấy lại.
 *
 * <p>{@code reveal_after_end} chỉ nói rằng kỳ thi <i>cho phép</i> công bố; người bấm vẫn là
 * người tổ chức.
 */
@RequiresRole(Role.ADMIN)
@Service
public class RevealStandingsUseCase {

    private final ContestRepository contests;
    private final StandingsEventBus bus;
    private final AuditLog auditLog;
    private final Clock clock;

    public RevealStandingsUseCase(ContestRepository contests, StandingsEventBus bus,
                                  AuditLog auditLog, Clock clock) {
        this.contests = contests;
        this.bus = bus;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    public void thucHien(long contestId) {
        Contest contest = contests.timTheoId(contestId)
                .orElseThrow(ContestsException::khongTimThay);
        if (!contest.daKetThuc(clock.instant())) {
            // Công bố giữa chừng là xoá luôn tác dụng của đóng băng — và không hoàn tác được.
            throw ContestsException.chuaKetThuc();
        }
        if (contest.daCongBo()) {
            return;   // idempotent: bấm hai lần không phải lỗi
        }

        contests.congBo(contestId, clock.instant());
        // Xoá cache bản đóng băng: từ giờ mọi người đọc bảng thật.
        bus.bangDaDoi(contestId);
        auditLog.ghi("CONTEST_STANDINGS_REVEALED", "contest", contestId,
                Map.of("slug", contest.slug()));
    }
}
