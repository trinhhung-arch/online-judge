package dev.oj.worker.pipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ca kiểm cho sự cố đo được 2026-09-10: API chết thì sáu slot ghi <b>12 dòng WARN mỗi giây</b>,
 * không đổi dù nó đã chết bao lâu, và mọi dòng log khác chìm nghỉm.
 *
 * <p>Hai tính chất phải giữ, và chúng độc lập nhau: nhịp chờ <i>giãn</i> theo từng slot, còn
 * dòng log thì <i>một</i> cho cả worker.
 */
class ClaimBackoffTest {

    private static final Duration NHIP = Duration.ofMillis(500);

    @Test
    @DisplayName("nhịp chờ nhân đôi mỗi lần hỏng rồi dừng ở trần")
    void nhip_gian_roi_cham_tran() {
        ClaimBackoff backoff = new ClaimBackoff(NHIP);

        assertThat(backoff.cho(1)).isEqualTo(Duration.ofMillis(500));
        assertThat(backoff.cho(2)).isEqualTo(Duration.ofSeconds(1));
        assertThat(backoff.cho(3)).isEqualTo(Duration.ofSeconds(2));
        assertThat(backoff.cho(6)).isEqualTo(Duration.ofSeconds(16));

        assertThat(backoff.cho(7))
                .as("trần = idle-poll × 60 = 30s, dưới xa lease 120s nên reaper không kịp "
                        + "thu hồi bài chỉ vì worker đang chờ")
                .isEqualTo(Duration.ofSeconds(30));
        assertThat(backoff.cho(1_000))
                .as("hỏng cả nghìn lần vẫn không vượt trần, và KHÔNG được tràn số")
                .isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("lienTiep < 1 là lỗi lập trình, phải ném chứ không trả bừa một con số")
    void lien_tiep_phai_tu_1() {
        ClaimBackoff backoff = new ClaimBackoff(NHIP);

        assertThatThrownBy(() -> backoff.cho(0)).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * ★ Ca giữ đúng thứ đã làm ngập log.
     *
     * <p>Sáu slot cùng phát hiện API chết trong cùng một khoảnh khắc — đúng điều xảy ra thật,
     * vì cả sáu cùng gọi {@code claim} vào một cổng đã đóng. Nếu mỗi slot tự ghi log thì đó
     * là sáu dòng cho một sự kiện, rồi lặp lại hai lần mỗi giây.
     */
    @Test
    @DisplayName("★ sáu slot cùng mất kết nối chỉ sinh ĐÚNG một dòng log")
    void sau_slot_cung_hong_chi_mot_dong_log() throws InterruptedException {
        ClaimBackoff backoff = new ClaimBackoff(NHIP);
        int soSlot = 6;
        AtomicInteger soLanGhi = new AtomicInteger();
        CountDownLatch cungLuc = new CountDownLatch(1);
        CountDownLatch xong = new CountDownLatch(soSlot);

        try (ExecutorService slots = Executors.newFixedThreadPool(soSlot)) {
            for (int i = 0; i < soSlot; i++) {
                slots.submit(() -> {
                    try {
                        cungLuc.await();
                        if (backoff.ghiNhanMat("Connection refused", NHIP)) {
                            soLanGhi.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        xong.countDown();
                    }
                });
            }
            cungLuc.countDown();
            assertThat(xong.await(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(soLanGhi.get())
                .as("%d slot cùng thấy API chết, nhưng đó là MỘT sự kiện của API chứ không "
                        + "phải %d sự kiện của %d slot", soSlot, soSlot, soSlot)
                .isEqualTo(1);
    }

    @Test
    @DisplayName("★ báo hồi phục đúng một lần, và chỉ khi trước đó đang mất")
    void bao_hoi_phuc_dung_mot_lan() {
        ClaimBackoff backoff = new ClaimBackoff(NHIP);

        assertThat(backoff.ghiNhanCoLai())
                .as("chưa từng mất kết nối thì không có gì để báo — mỗi lần claim thành công "
                        + "đều gọi hàm này, ghi log ở đây là làm ngập theo chiều ngược lại")
                .isFalse();

        backoff.ghiNhanMat("Connection refused", NHIP);

        assertThat(backoff.ghiNhanCoLai()).isTrue();
        assertThat(backoff.ghiNhanCoLai()).isFalse();
    }

    @Test
    @DisplayName("mất → có lại → mất lần nữa thì báo lại, không im luôn")
    void mat_lai_lan_nua_van_bao() {
        ClaimBackoff backoff = new ClaimBackoff(NHIP);

        assertThat(backoff.ghiNhanMat("lần 1", NHIP)).isTrue();
        backoff.ghiNhanCoLai();

        assertThat(backoff.ghiNhanMat("lần 2", NHIP))
                .as("chống ngập KHÔNG được biến thành im lặng: sự cố thứ hai vẫn phải có "
                        + "một dòng của nó")
                .isTrue();
    }
}
