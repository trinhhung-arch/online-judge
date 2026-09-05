package dev.oj.identity.infrastructure;

import dev.oj.identity.domain.IdentityException;
import dev.oj.platform.config.AppPropertiesGia;
import dev.oj.platform.error.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * ★ Trần CPU của đường đăng nhập.
 *
 * <p>FR-AUTH-08 chỉ khoá các lượt SAI, nên một bot có tài khoản hợp lệ đăng nhập ĐÚNG liên
 * tục không chạm hàng rào nào. Đo ngày 2026-09-05: <b>17,5 lượt/giây làm nghẽn cả máy chấm</b>,
 * và lúc ấy chấm bài đứng. Semaphore ở đây là thứ duy nhất chặn được điều đó.
 *
 * <p>Các ca dưới đây chạy với cost 12 thật (~250ms mỗi lần băm) vì {@code AuthProperties}
 * crash nếu cost khác — và guard ấy đúng: nó bảo vệ mọi mật khẩu đã băm trong database. Nhịp
 * 250ms cũng chính là thứ làm ca đầu tiên xác định được, không cần latch.
 */
class BCryptPasswordHasherTest {

    private BCryptPasswordHasher hasher(int soSuat, Duration cho) {
        return new BCryptPasswordHasher(AppPropertiesGia.voiBcrypt(soSuat, cho));
    }

    @Test
    @DisplayName("★ vượt trần thì TỪ CHỐI NGAY, không xếp hàng vô hạn")
    void vuot_tran_thi_tu_choi() throws Exception {
        var h = hasher(1, Duration.ofMillis(20));
        var daBatDau = new CountDownLatch(1);
        var loiCuaA = new AtomicReference<Throwable>();

        // Luồng A giữ suất duy nhất trong ~250ms — thời gian băm thật của cost 12.
        Thread a = new Thread(() -> {
            try {
                daBatDau.countDown();
                h.bam("mat-khau-cua-a");
            } catch (RuntimeException e) {
                loiCuaA.set(e);
            }
        });
        a.start();
        assertThat(daBatDau.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(60);       // đủ để A vào trong, còn xa 250ms nên A vẫn đang giữ

        long t0 = System.nanoTime();
        Throwable bi = bat(() -> h.bam("cua-b"));
        long choMs = (System.nanoTime() - t0) / 1_000_000;

        a.join(5_000);

        assertThat(bi)
                .as("xếp hàng nghĩa là giữ luồng Tomcat, và 200 luồng bị giữ thì đọc đề, "
                        + "nộp bài, ghi verdict đều chết theo")
                .isInstanceOf(IdentityException.class)
                .hasFieldOrPropertyWithValue("kind", DomainException.Kind.RATE_LIMITED)
                .hasFieldOrPropertyWithValue("code", "identity.he_thong_ban");
        assertThat(choMs).as("bỏ tải sau ~20ms chứ không chờ hết 250ms").isLessThan(150L);
        assertThat(loiCuaA.get()).as("luồng đang giữ suất không được ảnh hưởng").isNull();
    }

    @Test
    @DisplayName("★ suất được TRẢ LẠI sau mỗi lần băm — quên trả là hết suất vĩnh viễn")
    void suat_duoc_tra_lai() {
        var h = hasher(1, Duration.ofSeconds(2));

        // Ba lượt tuần tự trên MỘT suất: chỉ chạy được nếu finally luôn release.
        String bam = h.bam("mat-khau");
        assertThat(h.khop("mat-khau", bam)).isTrue();
        assertThat(h.khop("sai", bam)).isFalse();
    }

    @Test
    @DisplayName("băm null vẫn qua hàng rào — nhánh chống dò thời gian không được đi tắt")
    void bam_null_van_ton_suat() {
        var h = hasher(1, Duration.ofSeconds(2));
        assertThatCode(() -> assertThat(h.khop("gi-do", null)).isFalse())
                .doesNotThrowAnyException();
    }

    private static Throwable bat(Runnable r) {
        try {
            r.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }
}
