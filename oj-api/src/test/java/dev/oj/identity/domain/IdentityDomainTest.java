package dev.oj.identity.domain;

import dev.oj.platform.security.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code identity.domain} — Java thuần, không Spring context, chạy dưới một giây
 * ({@code CLAUDE.md} mục 3 luật 1).
 */
class IdentityDomainTest {

    @Nested
    @DisplayName("Handle")
    class Handle {

        /**
         * ★ Cùng một quy tắc sống ở hai nơi: {@link User#HANDLE_REGEX} và
         * {@code ck_users_handle_format} trong V1. Test này đọc thẳng file migration và đối
         * chiếu — nếu ai đó nới regex ở Java mà quên database (hoặc ngược lại), nó đỏ ngay,
         * chứ không đợi tới lúc một người dùng thật gặp lỗi 500 khó hiểu.
         */
        @Test
        @DisplayName("★ regex ở Java trùng đúng CHECK trong V1")
        void trung_voi_rang_buoc_database() throws Exception {
            String v1 = Files.readString(Path.of(
                    "src/main/resources/db/migration/V1__nen_tang_users_languages_hosts.sql"),
                    StandardCharsets.UTF_8);

            assertThat(v1)
                    .describedAs("V1 phải chứa nguyên văn regex của User.HANDLE_REGEX")
                    .contains(User.HANDLE_REGEX);
        }

        /**
         * ★ Bản sao THỨ BA: thuộc tính {@code pattern} của ô tên đăng nhập trong
         * {@code login.html}.
         *
         * <p>Nó tồn tại vì câu của server liệt kê thứ được phép chứ không nói ô của người
         * dùng sai ở đâu — một người gõ "Hùng" nhận về một danh sách quy tắc và phải tự đoán
         * ra rằng vấn đề là dấu thanh. Trang kiểm trước và nói thẳng, nhưng cái giá là luật
         * này giờ sống ở ba nơi.
         *
         * <p>Bản HTML là bản dễ mục nhất: nới regex ở Java và V1 mà quên nó thì form từ chối
         * một cái tên server chấp nhận, và <b>không có gì báo</b> — người dùng chỉ thấy một
         * ô không cho qua. {@code login.js} đọc {@code pattern} từ DOM chứ không tự viết
         * regex, nên đúng một bản HTML là đủ cho cả frontend.
         */
        @Test
        @DisplayName("★ regex ở Java trùng đúng pattern của ô tên đăng nhập trong login.html")
        void trung_voi_form_dang_ky() throws Exception {
            String html = Files.readString(
                    Path.of("src/main/resources/static/login.html"), StandardCharsets.UTF_8);

            assertThat(html)
                    .describedAs("login.html phải chứa pattern=\"%s\"", User.HANDLE_REGEX)
                    .contains("pattern=\"" + User.HANDLE_REGEX + "\"");
        }

        @Test
        @DisplayName("nhận chữ, số, chấm, gạch dưới, gạch ngang — dài 3 tới 32")
        void chap_nhan_dang_hop_le() {
            for (String tot : new String[]{"abc", "a_b.c-d", "Nguoi123", "a".repeat(32)}) {
                User.kiemTraHandle(tot);
            }
        }

        @Test
        @DisplayName("từ chối quá ngắn, quá dài, khoảng trắng, ký tự tiếng Việt, ký tự SQL")
        void tu_choi_dang_sai() {
            for (String xau : new String[]{null, "", "ab", "a".repeat(33), "co khoang trang",
                    "nguoi-việt", "a'--", "a@b", "<script>"}) {
                assertThatThrownBy(() -> User.kiemTraHandle(xau))
                        .describedAs("handle '%s'", xau)
                        .isInstanceOf(IdentityException.class);
            }
        }

        @Test
        @DisplayName("chuẩn hoá hạ chữ thường và cắt khoảng trắng — khớp lower(handle) của index")
        void chuan_hoa() {
            assertThat(User.chuanHoaHandle("  NguoiDung  ")).isEqualTo("nguoidung");
            assertThat(User.chuanHoaHandle(null)).isNull();
        }
    }

    @Nested
    @DisplayName("★ Mật khẩu — trần 72 byte là một lỗi bảo mật thật")
    class MatKhau {

        @Test
        @DisplayName("dưới 8 ký tự bị từ chối")
        void qua_ngan() {
            assertThatThrownBy(() -> PasswordPolicy.kiemTra("1234567"))
                    .hasFieldOrPropertyWithValue("code", "identity.mat_khau_qua_ngan");
        }

        @Test
        @DisplayName("★ quá 72 BYTE bị từ chối — BCrypt cắt cụt im lặng ở đó")
        void qua_72_byte() {
            // Không có phép kiểm này thì hai mật khẩu dưới đây băm ra CÙNG một chuỗi, và cái
            // thứ hai mở được tài khoản của cái thứ nhất.
            String bay_muoi_hai = "a".repeat(72);
            PasswordPolicy.kiemTra(bay_muoi_hai);

            assertThatThrownBy(() -> PasswordPolicy.kiemTra(bay_muoi_hai + "phan-duoi-bi-cat"))
                    .hasFieldOrPropertyWithValue("code", "identity.mat_khau_qua_dai");
        }

        @Test
        @DisplayName("★ đếm theo BYTE chứ không theo ký tự — tiếng Việt tốn 2-3 byte mỗi chữ")
        void dem_theo_byte_khong_theo_ky_tu() {
            // 30 ký tự, nhưng 90 byte UTF-8. Đếm bằng length() sẽ cho lọt, và mật khẩu bị cắt.
            String tiengViet = "ậ".repeat(30);
            assertThat(tiengViet).hasSize(30);
            assertThat(tiengViet.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(72);

            assertThatThrownBy(() -> PasswordPolicy.kiemTra(tiengViet))
                    .hasFieldOrPropertyWithValue("code", "identity.mat_khau_qua_dai");
        }
    }

    @Nested
    @DisplayName("★ Bất biến #9 — không đối tượng nào tự in ra bí mật")
    class KhongRoRiQuaToString {

        @Test
        @DisplayName("Credentials.toString() không chứa băm mật khẩu")
        void credentials_khong_lo_bam() {
            var c = new Credentials(1L, "dev", Role.USER, UserStatus.ACTIVE,
                    "$2a$12$BAM-MAT-KHAU-THAT-KHONG-DUOC-VAO-LOG");

            assertThat(c.toString()).doesNotContain("$2a$12$", "BAM-MAT-KHAU-THAT");
        }

        @Test
        @DisplayName("RefreshTokenSecret.toString() không chứa giá trị thô")
        void token_khong_lo_gia_tri_tho() {
            var s = RefreshTokenSecret.sinh();

            assertThat(s.toString()).doesNotContain(s.giaTriTho());
        }

        @Test
        @DisplayName("băm token ổn định và là 64 ký tự hex — khớp CHAR(64) của schema")
        void bam_on_dinh() {
            var s = RefreshTokenSecret.sinh();

            assertThat(s.sha256Hex()).hasSize(64).matches("[0-9a-f]{64}");
            assertThat(RefreshTokenSecret.bam(s.giaTriTho())).isEqualTo(s.sha256Hex());
            assertThat(RefreshTokenSecret.sinh().giaTriTho()).isNotEqualTo(s.giaTriTho());
        }
    }

    @Nested
    @DisplayName("Vòng đời tài khoản và phiên")
    class VongDoi {

        @Test
        @DisplayName("chỉ ACTIVE mới đăng nhập được")
        void chi_active_dang_nhap_duoc() {
            assertThat(UserStatus.ACTIVE.canLogIn()).isTrue();
            assertThat(UserStatus.DISABLED.canLogIn()).isFalse();
            assertThat(UserStatus.ANONYMIZED.canLogIn()).isFalse();
        }

        @Test
        @DisplayName("tài khoản đã ẩn danh hoá không có mật khẩu nào khớp được")
        void an_danh_thi_khong_dang_nhap_duoc() {
            var c = new Credentials(1L, "cu", Role.USER, UserStatus.ANONYMIZED, null);

            assertThat(c.coTheDangNhap()).isFalse();
        }

        @Test
        @DisplayName("refresh token: thu hồi và hết hạn là hai chuyện khác nhau")
        void hieu_luc_cua_token() {
            Instant t = Instant.parse("2026-08-29T10:00:00Z");
            var song = new RefreshToken(1, 1, t, t.plusSeconds(600), null);
            var thuHoi = new RefreshToken(2, 1, t, t.plusSeconds(600), t.plusSeconds(1));
            var hetHan = new RefreshToken(3, 1, t.minusSeconds(600), t.minusSeconds(1), null);

            assertThat(song.conHieuLuc(t.plusSeconds(1))).isTrue();
            assertThat(thuHoi.conHieuLuc(t.plusSeconds(2))).isFalse();
            assertThat(hetHan.conHieuLuc(t)).isFalse();
            assertThat(hetHan.daThuHoi()).isFalse();   // hết hạn KHÔNG phải bị thu hồi
        }

        @Test
        @DisplayName("tên hiển thị sau ẩn danh hoá theo đúng frplan.md")
        void ten_sau_an_danh() {
            assertThat(User.tenHienThiSauAnDanh(1234)).isEqualTo("[đã xoá #1234]");
        }
    }
}
