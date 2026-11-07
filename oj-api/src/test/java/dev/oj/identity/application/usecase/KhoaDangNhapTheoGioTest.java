package dev.oj.identity.application.usecase;

import dev.oj.identity.application.SessionIssuer;
import dev.oj.identity.application.TotpChecker;
import dev.oj.identity.application.port.LoginAttemptRepository;
import dev.oj.identity.domain.IdentityException;
import dev.oj.identity.domain.UserStatus;
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.error.DomainException;
import dev.oj.platform.security.JwtService;
import dev.oj.platform.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KhoaDangNhapTheoGioTest {

    private static final Instant T0 = Instant.parse("2026-11-05T08:00:00Z");
    private static final String IP = "203.0.113.7";
    private static final String DUNG = "matkhau-tot-123";

    private IdentityFakes.UsersGia users;
    private LanThuTheoGio lanThu;
    private final AppProperties props = IdentityFakes.properties();

    private static final class LanThuTheoGio implements LoginAttemptRepository {

        private final List<Instant> lanSai = new ArrayList<>();
        Instant bayGio;
        Instant khoaToi;

        @Override
        public void ghiNhan(String handleDaThu, String clientIp, boolean thanhCong) {
            if (!thanhCong) {
                lanSai.add(bayGio);
            }
        }

        @Override
        public int demThatBaiTu(String dai, Instant moc) {
            return (int) lanSai.stream().filter(luc -> !luc.isBefore(moc)).count();
        }

        @Override
        public Optional<Instant> khoaToi(String clientIp) {
            return Optional.ofNullable(khoaToi);
        }

        @Override
        public void khoa(String dai, Instant toi, String lyDo) {
            khoaToi = toi;
        }
    }

    @BeforeEach
    void dung() {
        users = new IdentityFakes.UsersGia();
        lanThu = new LanThuTheoGio();
        var bam = new IdentityFakes.BamGia();
        users.them("nan-nhan", "nan-nhan@oj.test", Role.USER, UserStatus.ACTIVE, bam.bam(DUNG));
    }

    private SessionIssuer.Session dangNhapLuc(Instant luc, String matKhau) {
        lanThu.bayGio = luc;
        Clock dongHo = Clock.fixed(luc, ZoneOffset.UTC);
        var phatPhien = new SessionIssuer(new JwtService(props, dongHo),
                new IdentityFakes.TokensGia(), props, dongHo);
        var totp = new TotpChecker(new IdentityFakes.HaiLopGia(), new IdentityFakes.MaHoaGia(),
                props, new IdentityFakes.NhatKyGia(), dongHo);
        return new LoginUseCase(users, new IdentityFakes.BamGia(), lanThu, phatPhien, props,
                dongHo, totp, userId -> { }).thucHien("nan-nhan", matKhau, "curl", IP, null);
    }

    private void saiLuc(Instant luc) {
        assertThatThrownBy(() -> dangNhapLuc(luc, "sai-roi"))
                .as("lượt sai lúc %s", luc)
                .isInstanceOf(IdentityException.class)
                .hasFieldOrPropertyWithValue("code", "identity.sai_thong_tin");
    }

    @Test
    @DisplayName("★ DN-05 · 5 lần sai trải đúng 60 giây (lần đầu ở giây 0, lần cuối ở giây 60) → vẫn khoá")
    void nam_lan_sai_trong_dung_60_giay_thi_khoa() {
        for (int giay : new int[]{0, 15, 30, 45}) {
            saiLuc(T0.plusSeconds(giay));
        }
        saiLuc(T0.plusSeconds(60));

        assertThat(lanThu.khoaToi).isEqualTo(T0.plusSeconds(60).plus(Duration.ofMinutes(15)));
    }

    @Test
    @DisplayName("★ DN-05 · 4 lần sai, 61 giây sau sai thêm 1 → KHÔNG khoá: lần sai cũ đã ra khỏi cửa sổ")
    void lan_sai_cu_qua_60_giay_thi_khong_tinh() {
        for (int i = 0; i < 4; i++) {
            saiLuc(T0);
        }
        Instant sau = T0.plusSeconds(61);
        saiLuc(sau);

        assertThat(lanThu.khoaToi).as("người gõ nhầm rải rác không được bị khoá oan").isNull();
        assertThatCode(() -> dangNhapLuc(sau, DUNG)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★ DN-06 · đang khoá, còn 1 giây → 429 kèm Retry-After 1 giây, kể cả với mật khẩu đúng")
    void con_mot_giay_thi_van_khoa() {
        for (int i = 0; i < 5; i++) {
            saiLuc(T0);
        }

        assertThatThrownBy(() -> dangNhapLuc(T0.plus(Duration.ofMinutes(15)).minusSeconds(1), DUNG))
                .isInstanceOf(IdentityException.class)
                .hasFieldOrPropertyWithValue("kind", DomainException.Kind.RATE_LIMITED)
                .hasFieldOrPropertyWithValue("code", "identity.khoa_tam")
                .hasFieldOrPropertyWithValue("retryAfter", Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("★ DN-06 · đúng mốc hết khoá → được xét lại bình thường: mật khẩu sai báo sai, mật khẩu đúng vào được")
    void dung_moc_het_khoa_thi_duoc_xet() {
        for (int i = 0; i < 5; i++) {
            saiLuc(T0);
        }
        Instant hetKhoa = T0.plus(Duration.ofMinutes(15));

        saiLuc(hetKhoa);
        assertThat(lanThu.khoaToi).as("một lần sai sau khi hết khoá không được khoá lại ngay")
                .isEqualTo(hetKhoa);
        assertThatCode(() -> dangNhapLuc(hetKhoa, DUNG)).doesNotThrowAnyException();
    }
}
