package dev.oj.identity.application.usecase;

import dev.oj.identity.application.TotpChecker;
import dev.oj.identity.domain.IdentityException;
import dev.oj.identity.domain.Totp;
import dev.oj.platform.error.DomainException;
import dev.oj.platform.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FR-AUTH-09 · V11 — xác thực hai lớp. */
class TwoFactorUseCaseTest {

    private static final Instant BAY_GIO = Instant.parse("2026-09-05T10:00:00Z");
    private static final String MAT_KHAU = "matkhau-tot-123";

    private IdentityFakes.UsersGia users;
    private IdentityFakes.BamGia hasher;
    private IdentityFakes.NhatKyGia nhatKy;
    private IdentityFakes.HaiLopGia haiLop;
    private IdentityFakes.MaHoaGia maHoa;
    private long userId;

    @BeforeEach
    void setUp() {
        users = new IdentityFakes.UsersGia();
        hasher = new IdentityFakes.BamGia();
        nhatKy = new IdentityFakes.NhatKyGia();
        haiLop = new IdentityFakes.HaiLopGia();
        maHoa = new IdentityFakes.MaHoaGia();
        userId = users.taoMoi("quan-tri", "qt@oj.test", "Quản trị", hasher.bam(MAT_KHAU));
    }

    private TotpChecker checker() {
        return new TotpChecker(haiLop, maHoa, hasher, Clock.fixed(BAY_GIO, ZoneOffset.UTC));
    }

    private TwoFactorUseCase useCase() {
        return new TwoFactorUseCase(IdentityFakes.nguoiGoi(userId, Role.ADMIN), users, haiLop,
                maHoa, hasher, checker(), nhatKy, Clock.fixed(BAY_GIO, ZoneOffset.UTC));
    }

    /** Bí mật nằm trong fake dưới dạng "enc:<thô>", nên test tính được mã đúng như app. */
    private String maDung() {
        String biMat = maHoa.giaiMa(haiLop.tim(userId).orElseThrow().secretEnc());
        long giay = BAY_GIO.getEpochSecond();
        for (int i = 0; i < 1_000_000; i++) {
            String ma = String.format("%06d", i);
            if (Totp.kiem(biMat, ma, giay) != null) {
                return ma;
            }
        }
        throw new AssertionError("không dò được mã — bản cài đặt hỏng");
    }

    @Nested
    @DisplayName("Đăng ký thiết bị")
    class DangKy {

        @Test
        @DisplayName("★ bước 1 KHÔNG bật gì — quét hụt mã QR không được khoá người dùng ra ngoài")
        void buoc_mot_chua_bat() {
            var banNhap = useCase().batDau();

            assertThat(banNhap.secretBase32()).hasSize(32);
            assertThat(banNhap.otpauthUri()).contains("otpauth://totp/");
            assertThat(haiLop.tim(userId)).get()
                    .extracting("enabled").isEqualTo(false);
            assertThat(checker().dangBat(userId))
                    .as("chưa xác nhận thì đăng nhập KHÔNG được đòi mã")
                    .isFalse();
        }

        @Test
        @DisplayName("bước 2 với mã đúng thì bật, và trả đúng 10 mã dự phòng")
        void buoc_hai_bat_va_tra_ma_du_phong() {
            useCase().batDau();

            List<String> duPhong = useCase().xacNhan(maDung());

            assertThat(duPhong).hasSize(10).allMatch(m -> m.matches("[A-Z2-7]{10}"));
            assertThat(checker().dangBat(userId)).isTrue();
            assertThat(haiLop.maDuPhongChuaDung(userId)).hasSize(10);
        }

        @Test
        @DisplayName("bước 2 với mã sai thì KHÔNG bật")
        void ma_sai_thi_khong_bat() {
            useCase().batDau();
            var uc = useCase();

            assertThatThrownBy(() -> uc.xacNhan("000000"))
                    .isInstanceOf(IdentityException.class)
                    .hasFieldOrPropertyWithValue("code", "identity.totp_sai");
            assertThat(checker().dangBat(userId)).isFalse();
        }

        @Test
        @DisplayName("★ đã bật rồi thì bắt đầu lại phải HỎNG — không âm thầm thay bí mật")
        void da_bat_thi_khong_ghi_de() {
            useCase().batDau();
            useCase().xacNhan(maDung());
            var uc = useCase();

            assertThatThrownBy(uc::batDau)
                    .as("ghi đè nghĩa là một request lạ thay được bí mật đang dùng thật "
                            + "và khoá chính chủ ra ngoài")
                    .isInstanceOf(IdentityException.class)
                    .hasFieldOrPropertyWithValue("kind", DomainException.Kind.CONFLICT);
        }
    }

    @Nested
    @DisplayName("Kiểm mã")
    class KiemMa {

        @BeforeEach
        void batSan() {
            useCase().batDau();
            useCase().xacNhan(maDung());
        }

        @Test
        @DisplayName("★ CHỐNG PHÁT LẠI — cùng một mã dùng lần thứ hai phải hỏng")
        void chong_phat_lai() {
            // Mã dùng để xác nhận đã được ghi làm last_step, nên chính nó không dùng lại được.
            String ma = maDung();
            var c = checker();

            assertThatThrownBy(() -> c.kiem(userId, ma))
                    .as("không chặn thì ai đọc được một request cũ trong 30 giây vẫn vào được")
                    .isInstanceOf(IdentityException.class)
                    .hasFieldOrPropertyWithValue("code", "identity.totp_sai");
        }

        @Test
        @DisplayName("thiếu mã → can_totp, khác hẳn mã sai → totp_sai")
        void thieu_ma_khac_ma_sai() {
            var c = checker();
            assertThatThrownBy(() -> c.kiem(userId, null))
                    .hasFieldOrPropertyWithValue("code", "identity.can_totp");
            assertThatThrownBy(() -> c.kiem(userId, "   "))
                    .hasFieldOrPropertyWithValue("code", "identity.can_totp");
            assertThatThrownBy(() -> c.kiem(userId, "000000"))
                    .hasFieldOrPropertyWithValue("code", "identity.totp_sai");
        }

        @Test
        @DisplayName("★ mã dự phòng dùng được ĐÚNG MỘT LẦN")
        void ma_du_phong_dung_mot_lan() {
            haiLop.xoa(userId);
            useCase().batDau();
            List<String> duPhong = useCase().xacNhan(maDung());
            String mot = duPhong.get(0);
            var c = checker();

            assertThatCode(() -> c.kiem(userId, mot)).doesNotThrowAnyException();
            assertThat(haiLop.maDuPhongChuaDung(userId)).hasSize(9);

            assertThatThrownBy(() -> c.kiem(userId, mot))
                    .as("mã dự phòng là mật khẩu dùng một lần — lần hai phải hỏng")
                    .isInstanceOf(IdentityException.class);
        }
    }

    @Nested
    @DisplayName("Tắt")
    class Tat {

        @BeforeEach
        void batSan() {
            useCase().batDau();
            useCase().xacNhan(maDung());
        }

        @Test
        @DisplayName("★ tắt đòi CẢ mật khẩu lẫn mã — cướp được phiên vẫn không tắt được 2FA")
        void tat_doi_ca_hai() {
            var uc = useCase();

            assertThatThrownBy(() -> uc.tat("sai-mat-khau", "000000"))
                    .isInstanceOf(IdentityException.class);
            assertThat(checker().dangBat(userId)).isTrue();

            assertThatThrownBy(() -> uc.tat(MAT_KHAU, "000000"))
                    .as("đúng mật khẩu nhưng sai mã thì vẫn không tắt được")
                    .isInstanceOf(IdentityException.class);
            assertThat(checker().dangBat(userId)).isTrue();
        }

        @Test
        @DisplayName("đủ mật khẩu và mã thì tắt, và xoá sạch cả mã dự phòng")
        void tat_thanh_cong() {
            // Bước tiếp theo nên cần một mã của bước khác — đẩy đồng hồ đi 30 giây.
            var ucSau = new TwoFactorUseCase(IdentityFakes.nguoiGoi(userId, Role.ADMIN), users,
                    haiLop, maHoa, hasher,
                    new TotpChecker(haiLop, maHoa, hasher,
                            Clock.fixed(BAY_GIO.plusSeconds(60), ZoneOffset.UTC)),
                    nhatKy, Clock.fixed(BAY_GIO.plusSeconds(60), ZoneOffset.UTC));

            String biMat = maHoa.giaiMa(haiLop.tim(userId).orElseThrow().secretEnc());
            long giay = BAY_GIO.plusSeconds(60).getEpochSecond();
            String maMoi = null;
            for (int i = 0; i < 1_000_000 && maMoi == null; i++) {
                String ma = String.format("%06d", i);
                Long buoc = Totp.kiem(biMat, ma, giay);
                if (buoc != null && buoc == Math.floorDiv(giay, Totp.BUOC_GIAY)) {
                    maMoi = ma;
                }
            }

            ucSau.tat(MAT_KHAU, maMoi);

            assertThat(haiLop.tim(userId)).isEmpty();
            assertThat(haiLop.maDuPhongChuaDung(userId)).isEmpty();
        }
    }
}
