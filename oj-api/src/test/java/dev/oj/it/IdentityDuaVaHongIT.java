package dev.oj.it;

import dev.oj.identity.application.port.EmailVerificationRepository;
import dev.oj.identity.application.usecase.ConfirmEmailUseCase;
import dev.oj.identity.application.usecase.LoginUseCase;
import dev.oj.identity.application.usecase.RefreshSessionUseCase;
import dev.oj.identity.application.usecase.RegisterUserUseCase;
import dev.oj.identity.domain.IdentityException;
import dev.oj.identity.domain.MaXacMinhEmail;
import dev.oj.identity.domain.RefreshTokenSecret;
import dev.oj.platform.error.DomainException;
import dev.oj.platform.security.GiaLapDanhTinh;
import dev.oj.platform.security.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentityDuaVaHongIT extends PostgresIT {

    private static final String MAT_KHAU = "matkhau-tot-123";
    private static final String MA_EMAIL = "246810";
    private static final String IP = "203.0.113.60";
    private static final String XONG = "xong";

    @Autowired RegisterUserUseCase dangKy;
    @Autowired LoginUseCase dangNhap;
    @Autowired RefreshSessionUseCase lamMoi;
    @Autowired ConfirmEmailUseCase xacNhanEmail;
    @Autowired EmailVerificationRepository maXacMinh;

    @AfterEach
    void huyDiemHen() {
        DiemHenSql.huyHet();
    }

    @Test
    @DisplayName("★ DK-08 · hai lượt đăng ký cùng handle chen nhau → đúng MỘT tài khoản; lượt thua nhận 409 từ khoá duy nhất, không 500")
    void dang_ky_trung_chen_nhau() throws InterruptedException {
        var ketQuaA = new AtomicReference<Object>();
        var ketQuaB = new AtomicReference<Object>();
        var bDaToi = new CountDownLatch(1);
        var aXong = new CountDownLatch(1);
        Thread b = luong("dang-ky-B", ketQuaB, () -> dangKy.thucHien(
                "Dua-Dang-Ky", "b-dua@oj.test", "Người B", MAT_KHAU, "198.51.100.82", null));
        Thread a = luong("dang-ky-A", ketQuaA, () -> {
            cho(bDaToi);
            try {
                return dangKy.thucHien(
                        "dua-dang-ky", "a-dua@oj.test", "Người A", MAT_KHAU, "198.51.100.81", null);
            } finally {
                aXong.countDown();
            }
        });
        DiemHenSql.hen(b, "INSERT INTO users", bDaToi, () -> aXong.getCount() == 0);

        chayVaChoXong(b, a);

        assertThat(ketQuaA.get()).as("lượt A").isInstanceOf(Long.class);
        assertThat(ketQuaB.get()).as("lượt B").isInstanceOfSatisfying(IdentityException.class, e -> {
            assertThat(e.kind()).isEqualTo(DomainException.Kind.CONFLICT);
            assertThat(e.code()).isEqualTo("identity.da_ton_tai");
            assertThat(e.publicMessage()).startsWith("Tên đăng nhập hoặc email");
        });
        assertThat(jdbc.sql("SELECT count(*)::int FROM users WHERE lower(handle) = 'dua-dang-ky'")
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ EM-09 · hai request xác nhận cùng một mã chen nhau → đúng MỘT lượt thành công, lượt kia 400, và không ai bị tính là gõ sai")
    void xac_nhan_email_chen_nhau() throws InterruptedException {
        long maId = maXacMinh.luu(USER_ID, MaXacMinhEmail.bam(MA_EMAIL),
                Instant.now().plus(Duration.ofMinutes(30)));
        var ketQuaA = new AtomicReference<Object>();
        var ketQuaB = new AtomicReference<Object>();
        var bDaToi = new CountDownLatch(1);
        var aXong = new CountDownLatch(1);
        Thread b = luong("xac-nhan-B", ketQuaB, this::xacNhanBangDev);
        Thread a = luong("xac-nhan-A", ketQuaA, () -> {
            cho(bDaToi);
            try {
                return xacNhanBangDev();
            } finally {
                aXong.countDown();
            }
        });
        DiemHenSql.hen(b, "SET consumed_at", bDaToi, () -> aXong.getCount() == 0);

        chayVaChoXong(b, a);

        assertThat(ketQuaA.get()).as("lượt A").isEqualTo(XONG);
        assertThat(ketQuaB.get()).as("lượt B").isInstanceOfSatisfying(IdentityException.class,
                e -> assertThat(e.code()).isEqualTo("identity.ma_xac_minh_sai"));
        assertThat(jdbc.sql("SELECT email_verified_at IS NOT NULL FROM users WHERE id = :id")
                .param("id", USER_ID).query(Boolean.class).single()).isTrue();
        assertThat(soDongAudit("EMAIL_VERIFIED")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT attempts FROM email_verifications WHERE id = :id")
                .param("id", maId).query(Integer.class).single())
                .as("lượt thua gõ ĐÚNG mã — không được trừ vào 5 lần thử của người dùng")
                .isZero();
    }

    @Test
    @DisplayName("★ PH-10 · phát token mới hỏng SAU khi đã thu hồi token cũ → rollback: token cũ sống lại, bấm lại được, không bị coi là trộm")
    void phat_token_hong_giua_chung_thi_token_cu_song_lai() {
        String cu = dangNhap.thucHien("dev", MAT_KHAU_DEV, "it-trinh-duyet", IP, null).refreshToken();

        assertThatThrownBy(() -> lamMoi.thucHien(cu, "trinh-duyet\u0000hong", IP))
                .isInstanceOf(DataAccessException.class);

        assertThat(daThuHoi(cu)).as("lượt thu hồi phải bị cuộn lại cùng lượt phát hỏng").isFalse();
        assertThat(jdbc.sql("SELECT count(*)::int FROM refresh_tokens WHERE user_id = :id")
                .param("id", USER_ID).query(Integer.class).single())
                .as("không để lại token mới mồ côi").isEqualTo(1);
        assertThat(soDongAudit("REFRESH_TOKEN_REUSE_DETECTED")).isZero();

        var moi = lamMoi.thucHien(cu, "it-trinh-duyet", IP);

        assertThat(moi.refreshToken()).isNotEqualTo(cu);
        assertThat(daThuHoi(cu)).isTrue();
        assertThat(soDongAudit("REFRESH_TOKEN_REUSE_DETECTED")).isZero();
    }

    private Object xacNhanBangDev() {
        try (var danhTinh = GiaLapDanhTinh.dongVai(USER_ID, "dev", Role.USER)) {
            xacNhanEmail.thucHien(MA_EMAIL);
            return XONG;
        }
    }

    private boolean daThuHoi(String token) {
        return jdbc.sql("SELECT revoked_at IS NOT NULL FROM refresh_tokens WHERE token_sha256 = :h")
                .param("h", RefreshTokenSecret.bam(token)).query(Boolean.class).single();
    }

    private int soDongAudit(String hanhDong) {
        return jdbc.sql("SELECT count(*)::int FROM audit_log WHERE action = :a")
                .param("a", hanhDong).query(Integer.class).single();
    }

    private static Thread luong(String ten, AtomicReference<Object> ketQua, Callable<?> viec) {
        return new Thread(() -> {
            try {
                ketQua.set(viec.call());
            } catch (Throwable e) {
                ketQua.set(e);
            }
        }, ten);
    }

    private static void chayVaChoXong(Thread... cac) throws InterruptedException {
        for (Thread t : cac) {
            t.start();
        }
        for (Thread t : cac) {
            t.join(DiemHenSql.TRAN_CHO.multipliedBy(3).toMillis());
            assertThat(t.isAlive()).as("luồng %s treo", t.getName()).isFalse();
        }
    }

    private static void cho(CountDownLatch latch) {
        try {
            if (!latch.await(DiemHenSql.TRAN_CHO.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError("luồng kia không tới điểm hẹn sau " + DiemHenSql.TRAN_CHO
                        + " — câu SQL của điểm hẹn đã bị viết lại?");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
