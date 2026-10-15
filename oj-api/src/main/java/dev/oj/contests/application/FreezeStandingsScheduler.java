package dev.oj.contests.application;

import dev.oj.contests.application.port.ContestRepository;
import dev.oj.contests.application.port.StandingsLock;
import dev.oj.contests.application.port.StandingsRepository;
import dev.oj.contests.domain.Contest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;

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
 *
 * <h2>★ Một bản chụp là MỘT transaction, đứng sau khoá của kỳ thi</h2>
 * Bản chụp là hai câu ghi: tổng theo người, rồi từng đề. Bản trước chạy chúng autocommit,
 * từng câu một. Câu thứ hai hỏng thì câu thứ nhất đã commit — và vì "đã chụp" được nhận ra
 * bằng chính những dòng ấy, kỳ thi <b>không bao giờ được chụp lại</b>: bảng đóng băng mất ô
 * từng đề và mất ô "?" vĩnh viễn. Hỏng không cần mạng đứt: ở READ COMMITTED mỗi câu chụp ảnh
 * dữ liệu riêng, nên một lô {@link StandingsUpdater} commit chen giữa hai câu thêm một người
 * mà câu thứ nhất chưa thấy — câu thứ hai chạm khoá ngoại và hỏng.
 *
 * <p>Nên cả ba bước — kiểm "đã chụp chưa", hai câu ghi — nằm trong một transaction, và câu
 * đầu tiên là {@link StandingsLock}: không lô nào commit chen được, nên hai câu đọc cùng một
 * bảng. Hỏng thì cả bản rollback và nhịp sau chụp lại thật.
 */
@Component
public class FreezeStandingsScheduler {

    private static final Logger log = LoggerFactory.getLogger(FreezeStandingsScheduler.class);

    private final ContestRepository contests;
    private final StandingsRepository standings;
    private final StandingsLock khoa;
    private final Clock clock;
    private final TransactionTemplate tx;

    public FreezeStandingsScheduler(ContestRepository contests, StandingsRepository standings,
                                    StandingsLock khoa, Clock clock,
                                    @Qualifier("appTransactionManager")
                                    PlatformTransactionManager txManager) {
        this.contests = contests;
        this.standings = standings;
        this.khoa = khoa;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
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
        List<Contest> canDongBang;
        try {
            canDongBang = contests.canDongBang(clock.instant());
        } catch (RuntimeException e) {
            log.error("Không đọc được danh sách kỳ thi cần đóng băng — thử lại ở nhịp sau", e);
            return;
        }
        // Bắt theo TỪNG kỳ thi. Bọc cả vòng thì một kỳ thi ném ở mọi nhịp làm mọi kỳ thi xếp
        // sau nó không được chụp — và chụp trễ là chụp kết quả SAU freeze_at, tức lộ đúng phần
        // mà đóng băng sinh ra để giấu (javadoc lớp).
        for (Contest c : canDongBang) {
            try {
                chup(c);
            } catch (RuntimeException e) {
                // Chụp nguyên khối (javadoc lớp) nên "thử lại" là thật. Nhưng mỗi nhịp trễ là
                // thêm kết quả sau freeze_at lọt vào bản chụp — cần người xem, không chỉ chờ.
                log.error("Đóng băng contest {} (freeze_at {}) hỏng — đã rollback, nhịp sau chụp "
                        + "lại; chụp càng trễ càng lộ kết quả sau giờ đóng băng",
                        c.id(), c.freezeAt(), e);
            }
        }
    }

    private void chup(Contest contest) {
        Boolean vuaChup = tx.execute(status -> {
            // Khoá TRƯỚC, bằng một câu riêng — luật thứ hai của StandingsLock.
            khoa.khoaKyThi(contest.id());
            if (standings.daChupDongBang(contest.id())) {
                return false;
            }
            standings.chupDongBang(contest.id(), contest.freezeAt());
            return true;
        });
        if (Boolean.TRUE.equals(vuaChup)) {
            log.info("Đã đóng băng bảng xếp hạng contest {} tại {}",
                    contest.id(), contest.freezeAt());
        }
    }
}
