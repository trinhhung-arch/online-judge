package dev.oj.identity.application.usecase;

import dev.oj.identity.application.EmailVerificationIssuer;
import dev.oj.identity.domain.IdentityException;
import dev.oj.identity.domain.MaXacMinhEmail;
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.config.AppPropertiesGia;
import dev.oj.platform.config.EmailVerificationProperties;
import dev.oj.platform.error.DomainException;
import dev.oj.platform.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Xác minh email — FR-AUTH-09 (V13), mức mềm. Chạy bằng fake, không Postgres, không SMTP.
 *
 * <h2>Ba hàng rào được kiểm ở đây, và vì sao cả ba đều cần một ca riêng</h2>
 * Mã chỉ có 6 chữ số. Nó an toàn <b>chỉ khi</b> ba thứ cùng có hiệu lực: trần số lần thử ·
 * hạn dùng · mã mới huỷ mã cũ ({@code V13__xac_minh_email.sql}). Bỏ mất một trong ba thì
 * <b>mọi ca chức năng vẫn xanh</b> — mã đúng vẫn xác minh được, mã sai vẫn bị từ chối — và
 * thứ duy nhất biến mất là độ khó của việc dò. Nên mỗi hàng rào có một ca nói thẳng về nó.
 *
 * <h2>Đồng hồ giả được, và đó là điều kiện để kiểm hạn dùng lẫn khoảng chờ</h2>
 * {@link DongHo} tiến lên bằng một lời gọi thay vì bằng {@code Thread.sleep}. Một bộ test
 * ngủ 60 giây để kiểm một khoảng chờ 60 giây là một bộ test người ta thôi chạy.
 */
class XacMinhEmailUseCaseTest {

    private static final Instant BAY_GIO = Instant.parse("2026-09-20T10:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(30);
    private static final Duration CHO = Duration.ofSeconds(60);
    private static final int TOI_DA = 5;

    private IdentityFakes.UsersGia users;
    private IdentityFakes.XacMinhEmailGia maXacMinh;
    private IdentityFakes.ThuGia hopThu;
    private IdentityFakes.NhatKyGia nhatKy;
    private DongHo dongHo;
    private AppProperties props;
    private long userId;

    /** Đồng hồ tiến được — {@link Clock#fixed} thì không kiểm được thứ gì có hạn. */
    private static final class DongHo extends Clock {

        private Instant luc;

        DongHo(Instant luc) {
            this.luc = luc;
        }

        void tienLen(Duration bao) {
            luc = luc.plus(bao);
        }

        @Override
        public Instant instant() {
            return luc;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @BeforeEach
    void dung() {
        users = new IdentityFakes.UsersGia();
        maXacMinh = new IdentityFakes.XacMinhEmailGia();
        hopThu = new IdentityFakes.ThuGia();
        nhatKy = new IdentityFakes.NhatKyGia();
        dongHo = new DongHo(BAY_GIO);
        maXacMinh.bayGio = BAY_GIO;
        props = batXacMinh(TOI_DA);
        userId = users.them("nguoi", "nguoi@oj.test", Role.USER,
                dev.oj.identity.domain.UserStatus.ACTIVE, "bam");
    }

    private static AppProperties batXacMinh(int soLanToiDa) {
        return AppPropertiesGia.voiXacMinhEmail(new EmailVerificationProperties(
                true, "oj@vi-du.test", TTL, soLanToiDa, CHO));
    }

    /** Đẩy cả đồng hồ của use-case lẫn đồng hồ của repository giả — hai bên phải cùng giờ. */
    private void tienLen(Duration bao) {
        dongHo.tienLen(bao);
        maXacMinh.bayGio = dongHo.instant();
    }

    private EmailVerificationIssuer issuer() {
        return new EmailVerificationIssuer(maXacMinh, hopThu, props, dongHo);
    }

    private SendVerificationEmailUseCase gui() {
        return new SendVerificationEmailUseCase(IdentityFakes.nguoiGoi(userId, Role.USER),
                users, maXacMinh, issuer(), props, dongHo);
    }

    private ConfirmEmailUseCase xacNhan() {
        return new ConfirmEmailUseCase(IdentityFakes.nguoiGoi(userId, Role.USER),
                users, maXacMinh, nhatKy, props);
    }

    private boolean daXacMinh() {
        return users.timTheoId(userId).orElseThrow().daXacMinhEmail();
    }

    // =========================================================================

    @Nested
    @DisplayName("Đăng ký phát mã và gửi thư")
    class LucDangKy {

        private RegisterUserUseCase dangKy() {
            return new RegisterUserUseCase(users, new IdentityFakes.BamGia(), nhatKy,
                    new IdentityFakes.ChanDangKyGia(), (t, ip) -> { }, issuer());
        }

        @Test
        @DisplayName("đăng ký xong thì có đúng MỘT lá thư, và tài khoản CHƯA xác minh")
        void dang_ky_gui_mot_thu() {
            long moi = dangKy().thucHien("nguoi-moi", "Moi@OJ.test", "Người mới",
                    "matkhau-tot-123", "203.0.113.7", "captcha");

            assertThat(hopThu.daGui).hasSize(1);
            assertThat(hopThu.daGui.getFirst().ma()).hasSize(6).containsOnlyDigits();
            assertThat(hopThu.daGui.getFirst().den())
                    .as("gửi tới địa chỉ ĐÃ CHUẨN HOÁ, không phải chuỗi người dùng gõ")
                    .isEqualTo("moi@oj.test");
            assertThat(users.timTheoId(moi).orElseThrow().daXacMinhEmail())
                    .as("mức mềm: đăng ký xong là dùng được ngay, nhưng nhãn thì chưa có")
                    .isFalse();
        }

        @Test
        @DisplayName("★ SMTP hỏng thì lượt đăng ký VẪN thành công")
        void smtp_hong_van_dang_ky_duoc() {
            hopThu.hong = true;

            long moi = dangKy().thucHien("nguoi-moi", "moi@oj.test", "Người mới",
                    "matkhau-tot-123", "203.0.113.7", "captcha");

            // Đây là điều kiện để thêm SMTP vào hệ thống này: một sự cố của nhà cung cấp thư
            // KHÔNG được biến thành "không ai đăng ký được". Tài khoản có thật, dùng được
            // ngay, và nút gửi lại ở trang hồ sơ là đường phục hồi.
            assertThat(users.timTheoId(moi)).isPresent();
            assertThat(hopThu.daGui).isEmpty();
        }

        @Test
        @DisplayName("tính năng TẮT thì không sinh mã và không gửi gì")
        void tat_thi_khong_sinh_ma() {
            props = AppPropertiesGia.macDinh();     // xác minh email tắt

            long moi = dangKy().thucHien("nguoi-moi", "moi@oj.test", "Người mới",
                    "matkhau-tot-123", "203.0.113.7", "captcha");

            assertThat(hopThu.daGui).isEmpty();
            assertThat(maXacMinh.timMaDangSong(moi)).isEmpty();
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("Xác nhận bằng mã")
    class XacNhan {

        @BeforeEach
        void coMa() {
            gui().thucHien();
        }

        @Test
        @DisplayName("mã đúng → đã xác minh, mã bị tiêu, và audit_log ghi lại")
        void ma_dung() {
            xacNhan().thucHien(hopThu.maMoiNhat());

            assertThat(daXacMinh()).isTrue();
            assertThat(maXacMinh.timMaDangSong(userId))
                    .as("mã dùng xong phải chết — nếu không thì nó còn sống thêm 30 phút")
                    .isEmpty();
            assertThat(nhatKy.hanhDong).contains("EMAIL_VERIFIED");
            assertThat(nhatKy.chiTiet)
                    .as("không ghi email vào audit_log: ADMIN đọc được bảng đó (bất biến #9)")
                    .allSatisfy(ct -> assertThat(ct.toString()).doesNotContain("@"));
        }

        @Test
        @DisplayName("mã đúng dán kèm khoảng trắng vẫn nhận — người ta CHÉP từ thư")
        void ma_dung_co_khoang_trang() {
            assertThatCode(() -> xacNhan().thucHien("  " + hopThu.maMoiNhat() + "\n"))
                    .doesNotThrowAnyException();
            assertThat(daXacMinh()).isTrue();
        }

        @Test
        @DisplayName("★ mã sai → 400, và bộ đếm lần thử TĂNG LÊN")
        void ma_sai_thi_tang_bo_dem() {
            assertThatThrownBy(() -> xacNhan().thucHien(maKhac()))
                    .isInstanceOf(IdentityException.class)
                    .hasFieldOrPropertyWithValue("kind", DomainException.Kind.INVALID)
                    .hasFieldOrPropertyWithValue("code", "identity.ma_xac_minh_sai");

            // Vế thứ hai mới là vế quan trọng. Nếu ConfirmEmailUseCase dùng @Transactional
            // TRẦN thay vì noRollbackFor, ngoại lệ trên cuốn trôi lượt tăng này — và trần số
            // lần thử biến mất trong im lặng, trong khi mọi ca chức năng vẫn xanh.
            assertThat(maXacMinh.timMaDangSong(userId)).get()
                    .extracting("soLanThu").isEqualTo(1);
            assertThat(daXacMinh()).isFalse();
        }

        @Test
        @DisplayName("★ sai đủ số lần thì mã CHẾT — kể cả sau đó gõ đúng")
        void sai_qua_nhieu_thi_ma_chet() {
            String maDung = hopThu.maMoiNhat();
            for (int i = 0; i < TOI_DA - 1; i++) {
                assertThatThrownBy(() -> xacNhan().thucHien(maKhac()))
                        .hasFieldOrPropertyWithValue("code", "identity.ma_xac_minh_sai");
            }

            assertThatThrownBy(() -> xacNhan().thucHien(maKhac()))
                    .hasFieldOrPropertyWithValue("code", "identity.ma_xac_minh_da_chet");

            // Đây là thứ làm cho 1 000 000 khả năng đủ an toàn: không có lượt thứ sáu.
            assertThatThrownBy(() -> xacNhan().thucHien(maDung))
                    .hasFieldOrPropertyWithValue("code", "identity.ma_xac_minh_sai");
            assertThat(daXacMinh()).isFalse();
        }

        @Test
        @DisplayName("★ mã hết hạn thì không dùng được nữa")
        void ma_het_han() {
            String ma = hopThu.maMoiNhat();
            tienLen(TTL.plusSeconds(1));

            assertThatThrownBy(() -> xacNhan().thucHien(ma))
                    .hasFieldOrPropertyWithValue("code", "identity.ma_xac_minh_sai");
            assertThat(daXacMinh()).isFalse();
        }

        @Test
        @DisplayName("mã đã dùng không dùng lại được lần hai")
        void khong_dung_lai_duoc() {
            String ma = hopThu.maMoiNhat();
            xacNhan().thucHien(ma);

            assertThatThrownBy(() -> xacNhan().thucHien(ma))
                    .hasFieldOrPropertyWithValue("code", "identity.email_da_xac_minh");
        }

        @Test
        @DisplayName("ô trống KHÔNG tiêu một lượt thử — phạt sai người là phạt sai")
        void o_trong_khong_tieu_luot() {
            assertThatThrownBy(() -> xacNhan().thucHien("   "))
                    .hasFieldOrPropertyWithValue("code", "identity.ma_xac_minh_sai");

            assertThat(maXacMinh.timMaDangSong(userId)).get()
                    .extracting("soLanThu").isEqualTo(0);
        }

        /** Một mã 6 chữ số chắc chắn KHÁC mã đang sống. */
        private String maKhac() {
            String dung = hopThu.maMoiNhat();
            return "000000".equals(dung) ? "111111" : "000000";
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("Gửi lại mã")
    class GuiLai {

        @Test
        @DisplayName("★ bấm lại ngay → 429, và KHÔNG có lá thư thứ hai")
        void trong_khoang_cho_thi_429() {
            gui().thucHien();

            assertThatThrownBy(() -> gui().thucHien())
                    .isInstanceOf(IdentityException.class)
                    .hasFieldOrPropertyWithValue("kind", DomainException.Kind.RATE_LIMITED)
                    .hasFieldOrPropertyWithValue("code", "identity.gui_lai_qua_nhanh");

            assertThat(hopThu.daGui).hasSize(1);
        }

        @Test
        @DisplayName("★ hết khoảng chờ → mã CŨ chết, mã mới sống")
        void het_khoang_cho_thi_thay_ma() {
            gui().thucHien();
            String maCu = hopThu.maMoiNhat();
            tienLen(CHO.plusSeconds(1));

            gui().thucHien();

            assertThat(hopThu.daGui).hasSize(2);
            // Hàng rào thứ ba của V13. Không huỷ mã cũ thì mười lần bấm gửi lại là mười mã
            // cùng sống, tức là chia mười độ khó của cùng một lượt dò.
            assertThatThrownBy(() -> xacNhan().thucHien(maCu))
                    .hasFieldOrPropertyWithValue("code", "identity.ma_xac_minh_sai");
            assertThatCode(() -> xacNhan().thucHien(hopThu.maMoiNhat()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("★ mã cũ ĐÃ HẾT HẠN vẫn gửi lại được — nó chiếm chỗ trong unique index")
        void ma_het_han_khong_chan_gui_lai() {
            gui().thucHien();
            tienLen(TTL.plusSeconds(1));

            // Nếu huyMaCu() lỡ mang thêm điều kiện `expires_at > now()` thì câu chèn sau đó
            // đâm vào ux_email_verifications_song — một lỗi chỉ xuất hiện sau đúng 30 phút,
            // tức là không bao giờ gặp lúc đang viết code.
            assertThatCode(() -> gui().thucHien()).doesNotThrowAnyException();
            assertThat(hopThu.daGui).hasSize(2);
        }

        @Test
        @DisplayName("đã xác minh rồi thì không xin mã nữa — 409")
        void da_xac_minh_thi_409() {
            gui().thucHien();
            xacNhan().thucHien(hopThu.maMoiNhat());
            tienLen(CHO.plusSeconds(1));

            assertThatThrownBy(() -> gui().thucHien())
                    .hasFieldOrPropertyWithValue("kind", DomainException.Kind.CONFLICT)
                    .hasFieldOrPropertyWithValue("code", "identity.email_da_xac_minh");
        }

        @Test
        @DisplayName("★ máy chủ chưa bật → nói thẳng, không im lặng trả 204")
        void tat_thi_noi_thang() {
            props = AppPropertiesGia.macDinh();

            assertThatThrownBy(() -> gui().thucHien())
                    .hasFieldOrPropertyWithValue("code", "identity.xac_minh_email_tat");
            assertThat(hopThu.daGui).isEmpty();
        }

        @Test
        @DisplayName("★ hai cú bấm cùng lúc → 429, KHÔNG phải 500")
        void hai_request_song_song_thi_429() {
            // Cuộc đua thật: cả hai request cùng thấy không có mã sống, cùng huỷ, rồi cùng
            // chèn. ux_email_verifications_song để đúng một cái thắng. Người thua phải nhận
            // một câu đọc được — họ không làm gì sai, và mã đã nằm trong hộp thư rồi.
            maXacMinh.requestKhacChenTruoc = true;

            assertThatThrownBy(() -> gui().thucHien())
                    .isInstanceOf(IdentityException.class)
                    .hasFieldOrPropertyWithValue("kind", DomainException.Kind.RATE_LIMITED)
                    .hasFieldOrPropertyWithValue("code", "identity.gui_lai_qua_nhanh");
        }

        @Test
        @DisplayName("tài khoản đã ẩn danh hoá không còn địa chỉ để gửi tới")
        void an_danh_hoa_thi_khong_gui() {
            users.anDanhHoa(userId, "[đã xoá #" + userId + "]");

            assertThatThrownBy(() -> gui().thucHien())
                    .hasFieldOrPropertyWithValue("code", "identity.khong_tim_thay");
            assertThat(hopThu.daGui).isEmpty();
        }
    }
}
