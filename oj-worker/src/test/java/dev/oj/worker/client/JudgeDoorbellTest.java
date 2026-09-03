package dev.oj.worker.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ Bước 6.4 — chuông cửa. Ba tính chất, và cả ba đều là điều kiện để bước 6.4 an toàn.
 */
class JudgeDoorbellTest {

    private final JudgeDoorbell chuong = new JudgeDoorbell();

    /**
     * ★ Lý do tồn tại của cả bước: ngân sách {@code enqueue → claim} là 100ms, còn nhịp ngủ
     * là 500ms. Slot phải dậy <b>ngay</b> khi có chuông, không chờ hết nhịp.
     */
    @Test
    @DisplayName("★ slot đang chờ dậy NGAY khi có chuông, không chờ hết idle-poll")
    void day_ngay_khi_co_chuong() throws Exception {
        CountDownLatch daVaoCho = new CountDownLatch(1);
        AtomicLong daCho = new AtomicLong(-1);

        Thread slot = new Thread(() -> {
            long t0 = System.nanoTime();
            daVaoCho.countDown();
            chuong.cho(Duration.ofSeconds(30));
            daCho.set((System.nanoTime() - t0) / 1_000_000);
        });
        slot.start();
        assertThat(daVaoCho.await(2, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(50);          // chắc chắn slot đã vào wait()

        chuong.reo();
        slot.join(5_000);

        assertThat(daCho.get())
                .as("chờ 30 giây mà chuông reo sau 50ms thì phải dậy trong vòng vài trăm ms")
                .isBetween(0L, 2_000L);
    }

    /**
     * ★ Vế thứ hai, và là vế giữ cho bước 6.4 an toàn: <b>không có chuông thì vẫn dậy</b>.
     *
     * <p>Broker chết, reaper thả một bài, hay {@code publishEnqueued} hỏng sau commit — cả ba
     * đều là những trường hợp không có tiếng chuông nào. Bỏ nhịp chờ đi là biến RabbitMQ từ
     * đường dẫn thành kho chứa, và lúc đó R1 không còn được Postgres bảo đảm nữa.
     */
    @Test
    @DisplayName("★ không có chuông thì vẫn dậy sau idle-poll — đây là đường lùi của degraded mode")
    void van_day_khi_khong_ai_reo() {
        long t0 = System.nanoTime();

        boolean binhThuong = chuong.cho(Duration.ofMillis(120));

        long msDaCho = (System.nanoTime() - t0) / 1_000_000;
        assertThat(binhThuong).isTrue();
        assertThat(msDaCho).isGreaterThanOrEqualTo(100);
    }

    /**
     * Bước 6.8 — {@code JudgeLoop.stop()} rung chuông để đánh thức slot đang chờ. Không có
     * đường thoát này thì mỗi lần tắt máy đội thêm một nhịp {@code idle-poll} mà không vì lý
     * do gì.
     */
    @Test
    @DisplayName("bị ngắt thì trả false — tín hiệu tắt máy, không phải một lần dậy bình thường")
    void bi_ngat_thi_tra_false() throws Exception {
        AtomicLong ketQua = new AtomicLong(1);
        CountDownLatch daVaoCho = new CountDownLatch(1);

        Thread slot = new Thread(() -> {
            daVaoCho.countDown();
            ketQua.set(chuong.cho(Duration.ofSeconds(30)) ? 1 : 0);
        });
        slot.start();
        assertThat(daVaoCho.await(2, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(50);

        slot.interrupt();
        slot.join(5_000);

        assertThat(ketQua.get()).isZero();
    }
}
