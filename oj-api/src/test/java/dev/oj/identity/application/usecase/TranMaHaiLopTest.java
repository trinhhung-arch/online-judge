package dev.oj.identity.application.usecase;

import dev.oj.identity.application.TotpChecker;
import dev.oj.identity.domain.IdentityException;
import dev.oj.identity.domain.Totp;
import dev.oj.identity.domain.TwoFactor;
import dev.oj.platform.config.AppPropertiesGia;
import dev.oj.platform.error.DomainException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ★ V15 · trần mã hai lớp THEO TÀI KHOẢN (rà soát 2026-09-24, F2).
 *
 * <p>Người đã có mật khẩu ADMIN xoay được địa chỉ IP sau mỗi 5 lần sai — trần của FR-AUTH-08
 * không giữ được họ. Ca ở đây giữ điều mà trần IP không giữ: đếm trên TÀI KHOẢN ĐÍCH, và khi đã
 * khoá thì cả mã đúng cũng bị từ chối (không thì kẻ dò vẫn biết mình trúng).
 */
class TranMaHaiLopTest {

    private static final Instant BAY_GIO = Instant.parse("2026-09-24T10:00:00Z");
    private static final long USER = 7;

    private IdentityFakes.HaiLopGia haiLop;
    private IdentityFakes.MaHoaGia maHoa;
    private IdentityFakes.NhatKyGia nhatKy;
    private String biMat;

    @BeforeEach
    void setUp() {
        haiLop = new IdentityFakes.HaiLopGia();
        maHoa = new IdentityFakes.MaHoaGia();
        nhatKy = new IdentityFakes.NhatKyGia();
        biMat = Totp.sinhBiMat();
        haiLop.theoUser.put(USER, new TwoFactor(USER, maHoa.maHoa(biMat), true, null));
    }

    private TotpChecker checker(Instant luc) {
        return new TotpChecker(haiLop, maHoa, AppPropertiesGia.macDinh(), nhatKy,
                Clock.fixed(luc, ZoneOffset.UTC));
    }

    private String ma(Instant luc, boolean dung) {
        for (int i = 0; i < 1_000_000; i++) {
            String m = String.format("%06d", i);
            if ((Totp.kiem(biMat, m, luc.getEpochSecond()) != null) == dung) {
                return m;
            }
        }
        throw new AssertionError("không tìm được mã");
    }

    private void saiLan(int n, Instant luc) {
        String sai = ma(luc, false);
        for (int i = 0; i < n; i++) {
            assertThatThrownBy(() -> checker(luc).kiem(USER, sai))
                    .hasFieldOrPropertyWithValue("code", "identity.totp_sai");
        }
    }

    @Test
    @DisplayName("★ 10 mã sai liên tiếp → khoá; khi đang khoá, MÃ ĐÚNG cũng bị từ chối (429)")
    void muoi_ma_sai_thi_khoa_ca_ma_dung() {
        saiLan(10, BAY_GIO);

        assertThat(nhatKy.hanhDong).containsExactly("TWO_FACTOR_LOCKED");
        assertThatThrownBy(() -> checker(BAY_GIO).kiem(USER, ma(BAY_GIO, true)))
                .as("kiểm khoá SAU khi so mã thì kẻ dò vẫn biết mình trúng — và dò tiếp")
                .isInstanceOf(IdentityException.class)
                .hasFieldOrPropertyWithValue("code", "identity.hai_lop_tam_khoa")
                .hasFieldOrPropertyWithValue("kind", DomainException.Kind.RATE_LIMITED);
    }

    @Test
    @DisplayName("9 mã sai rồi một mã đúng → đếm lại từ 0: người gõ nhầm không tích luỹ tới khoá")
    void ma_dung_dat_lai_dem() {
        saiLan(9, BAY_GIO);
        assertThatCode(() -> checker(BAY_GIO).kiem(USER, ma(BAY_GIO, true))).doesNotThrowAnyException();

        Instant sau = BAY_GIO.plusSeconds(90);   // bước khác — mã cũ đã bị ghi là đã dùng
        saiLan(9, sau);

        assertThat(haiLop.khoaHaiLopToi(USER)).as("9 + 9 không liên tiếp — không được khoá").isEmpty();
        assertThatCode(() -> checker(sau).kiem(USER, ma(sau, true))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("hết 15 phút khoá thì mã đúng lại qua")
    void het_khoa_thi_qua() {
        saiLan(10, BAY_GIO);
        Instant sau = BAY_GIO.plus(Duration.ofMinutes(15)).plusSeconds(30);

        assertThatCode(() -> checker(sau).kiem(USER, ma(sau, true))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("thiếu mã KHÔNG tính là sai — đó là bước bình thường của luồng đăng nhập")
    void thieu_ma_khong_dem() {
        for (int i = 0; i < 12; i++) {
            assertThatThrownBy(() -> checker(BAY_GIO).kiem(USER, " "))
                    .hasFieldOrPropertyWithValue("code", "identity.can_totp");
        }

        assertThat(haiLop.maSai.getOrDefault(USER, 0)).isZero();
        assertThat(haiLop.khoaHaiLopToi(USER)).isEmpty();
    }

    @Test
    @DisplayName("phát lại một mã đã dùng tính là MỘT lần sai — đó chính là tín hiệu tấn công")
    void phat_lai_tinh_la_sai() {
        String dung = ma(BAY_GIO, true);
        checker(BAY_GIO).kiem(USER, dung);

        assertThatThrownBy(() -> checker(BAY_GIO).kiem(USER, dung))
                .hasFieldOrPropertyWithValue("code", "identity.totp_sai");
        assertThat(haiLop.maSai.get(USER)).isEqualTo(1);
    }
}
