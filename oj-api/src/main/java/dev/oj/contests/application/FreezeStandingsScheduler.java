package dev.oj.contests.application;

import dev.oj.contests.application.port.ContestRepository;
import dev.oj.contests.application.port.StandingsRepository;
import dev.oj.contests.domain.Contest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * ★ Chụp bảng xếp hạng đúng lúc {@code freeze_at} — FR-CON-05. Bước 5.8.
 *
 * <h2>Vì sao là {@code @Scheduled} chứ không phải một job trong bảng {@code jobs}</h2>
 * {@code build-order.md} gọi nó là "FreezeStandingsJob", nhưng {@code CHECK (type IN (...))}
 * của V6 không có loại nào cho việc này — và thêm một loại là một migration mới cho một việc
 * <b>không cần hạ tầng job</b>: nó chạy vài trăm mili giây, không có tiến độ để báo, không có
 * gì để huỷ giữa chừng, và không ai cần theo dõi nó.
 *
 * <p>Hạ tầng job tồn tại cho việc <i>dài và cần chạy tiếp được sau restart</i> (Quy tắc 5 của
 * {@code frplan.md}). Dùng nó cho một câu {@code INSERT ... SELECT} là trả tiền cho thứ không
 * dùng tới.
 *
 * <h2>Chỉ chụp MỘT lần, và điều đó được ép ở tầng dữ liệu</h2>
 * {@code canDongBang} loại sẵn kỳ thi đã có bản chụp, và {@code chupDongBang} dùng
 * {@code ON CONFLICT DO NOTHING}. Hai lớp, vì chỉ một lớp thì hai instance API cùng chạy nhịp
 * này sẽ cùng thấy "chưa chụp" và cùng chụp.
 *
 * <p>Chụp lại lần thứ hai không phải chuyện nhỏ: nó sẽ chụp bảng ở thời điểm <i>muộn hơn</i>
 * {@code freeze_at}, tức là lộ đúng phần kết quả mà đóng băng sinh ra để giấu.
 */
@Component
public class FreezeStandingsScheduler {

    private static final Logger log = LoggerFactory.getLogger(FreezeStandingsScheduler.class);

    private final ContestRepository contests;
    private final StandingsRepository standings;
    private final Clock clock;

    public FreezeStandingsScheduler(ContestRepository contests, StandingsRepository standings,
                                    Clock clock) {
        this.contests = contests;
        this.standings = standings;
        this.clock = clock;
    }

    /**
     * Nhịp 5 giây. Độ trễ tối đa giữa {@code freeze_at} và lúc chụp là một nhịp — và bản chụp
     * lấy dữ liệu <i>tại thời điểm chạy</i>, nên năm giây ấy là năm giây kết quả bị lộ thêm.
     *
     * <p>Chấp nhận được vì bảng xếp hạng cũng chỉ cập nhật mỗi hai giây (FR-CON-04), nên
     * "chính xác tới giây" là một độ chính xác mà hệ thống này chưa từng hứa.
     */
    @Scheduled(fixedDelay = 5000)
    public void nhip() {
        try {
            for (Contest c : contests.canDongBang(clock.instant())) {
                chup(c);
            }
        } catch (RuntimeException e) {
            log.error("Nhịp đóng băng hỏng — nuốt để bộ lập lịch không huỷ tác vụ", e);
        }
    }

    private void chup(Contest contest) {
        if (standings.daChupDongBang(contest.id())) {
            return;
        }
        standings.chupDongBang(contest.id(), contest.freezeAt());
        log.info("Đã đóng băng bảng xếp hạng contest {} tại {}",
                contest.id(), contest.freezeAt());
    }
}
