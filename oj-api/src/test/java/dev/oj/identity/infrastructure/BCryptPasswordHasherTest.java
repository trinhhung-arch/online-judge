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
        Thread.sleep(60);

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
