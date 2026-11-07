package dev.oj.identity.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class TotpTest {

    private static final String HAT_GIONG = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @ParameterizedTest(name = "T={0} → {1}")
    @DisplayName("★ vector chuẩn RFC 6238 Phụ lục B (SHA-1)")
    @CsvSource({
            "59,          287082",
            "1111111109,  081804",
            "1111111111,  050471",
            "1234567890,  005924",
            "2000000000,  279037",
            "20000000000, 353130",
    })
    void vector_chuan(long giay, String maMongDoi) {
        assertThat(Totp.kiem(HAT_GIONG, maMongDoi, giay))
                .as("mã %s phải hợp lệ tại T=%d", maMongDoi, giay)
                .isEqualTo(Math.floorDiv(giay, Totp.BUOC_GIAY));
    }

    @Test
    @DisplayName("mã của bước khác không khớp")
    void ma_sai_thi_khong_khop() {
        assertThat(Totp.kiem(HAT_GIONG, "287082", 1111111109L)).isNull();
    }

    @Test
    @DisplayName("cửa sổ ±1 bước — điện thoại lệch đồng hồ vài giây vẫn vào được")
    void cua_so_mot_buoc() {
        assertThat(Totp.kiem(HAT_GIONG, "287082", 29L)).isEqualTo(1L);
        assertThat(Totp.kiem(HAT_GIONG, "287082", 89L)).isEqualTo(1L);
        assertThat(Totp.kiem(HAT_GIONG, "287082", 119L))
                .as("bước 3 đã ngoài cửa sổ")
                .isNull();
    }

    @Test
    @DisplayName("★ trả về SỐ BƯỚC, không phải true/false — người gọi cần nó để chống phát lại")
    void tra_ve_so_buoc() {
        Long buoc = Totp.kiem(HAT_GIONG, "287082", 59L);
        assertThat(buoc)
                .as("không có số bước thì không lưu được last_step, và cùng một mã dùng "
                        + "lại được trong suốt 30 giây")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("đầu vào rác bị loại trước khi tính HMAC")
    void dau_vao_rac() {
        assertThat(Totp.kiem(HAT_GIONG, null, 59L)).isNull();
        assertThat(Totp.kiem(HAT_GIONG, "", 59L)).isNull();
        assertThat(Totp.kiem(HAT_GIONG, "12345", 59L)).isNull();
        assertThat(Totp.kiem(HAT_GIONG, "1234567", 59L)).isNull();
        assertThat(Totp.kiem(HAT_GIONG, "abcdef", 59L)).isNull();
        assertThat(Totp.kiem(HAT_GIONG, "28708 ", 59L)).isNull();
    }

    @Test
    @DisplayName("bí mật sinh ra là Base32 hợp lệ, 32 ký tự, và mỗi lần một khác")
    void sinh_bi_mat() {
        String a = Totp.sinhBiMat();
        String b = Totp.sinhBiMat();

        assertThat(a).hasSize(32).matches("[A-Z2-7]+");
        assertThat(a).isNotEqualTo(b);
        assertThat(Totp.kiem(a, maCuaChinhNo(a, 1_000_000L), 1_000_000L)).isNotNull();
    }

    @Test
    @DisplayName("URI otpauth mang đủ tham số và escape khoảng trắng trong issuer")
    void uri_otpauth() {
        String uri = Totp.uriOtpauth("Online Judge", "quan-tri", HAT_GIONG);

        assertThat(uri)
                .startsWith("otpauth://totp/Online%20Judge:quan-tri?")
                .contains("secret=" + HAT_GIONG)
                .contains("issuer=Online%20Judge")
                .contains("algorithm=SHA1")
                .contains("digits=6")
                .contains("period=30");
    }

    private static String maCuaChinhNo(String biMat, long giay) {
        for (int i = 0; i < 1_000_000; i++) {
            String ma = String.format("%06d", i);
            if (Totp.kiem(biMat, ma, giay) != null) {
                return ma;
            }
        }
        throw new AssertionError("không có mã nào khớp — bản cài đặt hỏng");
    }
}
