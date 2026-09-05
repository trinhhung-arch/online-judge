package dev.oj.identity.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ Một bản TOTP sai KHÔNG hỏng ồn ào: nó hoặc khoá vĩnh viễn người quản trị ra ngoài, hoặc
 * chấp nhận mã của người khác. Cả hai đều chỉ lộ ra khi đã quá muộn.
 *
 * <p>Nên ca đầu tiên là <b>vector chuẩn của RFC 6238 Phụ lục B</b>, không phải một mã do
 * chính code này sinh ra rồi tự kiểm lại — cách sau chỉ chứng minh code nhất quán với chính
 * nó, kể cả khi nó nhất quán sai.
 */
class TotpTest {

    /**
     * Hạt giống của RFC 6238: chuỗi ASCII {@code "12345678901234567890"} (20 byte),
     * mã hoá Base32.
     */
    private static final String HAT_GIONG = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    /**
     * RFC 6238 in mã 8 chữ số; sáu chữ số là sáu chữ số CUỐI của chúng, vì
     * {@code (x mod 10^8) mod 10^6 = x mod 10^6}.
     */
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
        // 287082 đúng ở T=59, tức bước 1. Nó phải còn hợp lệ ở bước 0 và bước 2.
        assertThat(Totp.kiem(HAT_GIONG, "287082", 29L)).isEqualTo(1L);   // bước 0
        assertThat(Totp.kiem(HAT_GIONG, "287082", 89L)).isEqualTo(1L);   // bước 2
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
        // Dùng được ngay: sinh ra rồi kiểm một mã của chính nó phải khớp.
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

    /** Dò mã đúng của một bí mật bất kỳ bằng cách thử — chỉ dùng trong test. */
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
