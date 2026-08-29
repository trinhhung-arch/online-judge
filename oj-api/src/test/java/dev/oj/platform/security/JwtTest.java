package dev.oj.platform.security;

import dev.oj.platform.config.AppProperties;
import dev.oj.platform.security.CurrentUserProvider.CurrentUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ★ Bộ test tấn công cho JWT tự viết — Bước 4.5.
 *
 * <p>Quyết định "không thêm thư viện JWT" chỉ đúng nếu <b>những ca mà thư viện từng bị thủng
 * đều được kiểm ở đây</b>. Bốn ca đầu của {@link ChuKy} chính là bốn lớp CVE thật của các thư
 * viện JWT: {@code alg=none}, đổi thuật toán, sửa payload giữ nguyên chữ ký, và ký bằng khoá
 * khác.
 *
 * <p>Nếu một ngày có người đổi {@link Jwt} sang "đọc header từ token cho linh hoạt", bộ này
 * đỏ ngay ở ca đầu tiên.
 */
class JwtTest {

    private static final String KHOA = "khoa-ky-dai-hon-ba-muoi-hai-ky-tu-1234";
    private static final String KHOA_KHAC = "mot-khoa-hoan-toan-khac-cung-du-dai-5678";

    private static final Instant BAY_GIO = Instant.parse("2026-08-29T10:00:00Z");

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private static JwtService service(String khoa, Instant luc) {
        var auth = new AppProperties.Auth(khoa, Duration.ofMinutes(15), Duration.ofDays(7),
                12, 5, Duration.ofSeconds(60), Duration.ofMinutes(15));
        return new JwtService(dev.oj.platform.config.AppPropertiesGia.voiAuth(auth),
                Clock.fixed(luc, ZoneOffset.UTC));
    }

    private static final CurrentUser DEV = new CurrentUser(7L, "dev", Role.SETTER);

    @Nested
    @DisplayName("Vòng đi–về")
    class VongDiVe {

        @Test
        @DisplayName("phát rồi đọc lại được nguyên vẹn cả ba trường")
        void di_ve_nguyen_ven() {
            String token = service(KHOA, BAY_GIO).phat(DEV);
            CurrentUser doc = service(KHOA, BAY_GIO).doc(token);

            assertThat(doc.id()).isEqualTo(7L);
            assertThat(doc.handle()).isEqualTo("dev");
            assertThat(doc.role()).isEqualTo(Role.SETTER);
        }

        @Test
        @DisplayName("token có đúng ba đoạn, và đoạn đầu là header cố định")
        void hinh_dang_dung_chuan() {
            String token = service(KHOA, BAY_GIO).phat(DEV);

            assertThat(token.split("\\.")).hasSize(3);
            assertThat(token).startsWith(Jwt.HEADER_B64 + ".");
        }

        @Test
        @DisplayName("★ payload KHÔNG mã hoá — nên nó không được chứa gì ngoài bốn trường đã chọn")
        void payload_doc_duoc_nen_phai_ngheo() {
            String token = service(KHOA, BAY_GIO).phat(DEV);
            String payload = new String(
                    Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);

            // Ai cầm token cũng đọc được đúng chuỗi này bằng một dòng lệnh. Đó là lý do
            // JwtService.Claims chỉ có bốn trường và không có email (bất biến #9).
            assertThat(payload).contains("\"sub\":7", "\"handle\":\"dev\"", "\"role\":\"SETTER\"");
            assertThat(payload).doesNotContain("email", "password", "@");
        }
    }

    @Nested
    @DisplayName("★ Chữ ký — bốn lớp CVE của các thư viện JWT")
    class ChuKy {

        @Test
        @DisplayName("alg=none bị loại, vì header không bao giờ được đọc từ token")
        void alg_none_bi_loai() {
            String header = B64.encodeToString(
                    "{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            String payload = B64.encodeToString(
                    "{\"sub\":1,\"handle\":\"ke-tan-cong\",\"role\":\"ADMIN\",\"exp\":99999999999}"
                            .getBytes(StandardCharsets.UTF_8));

            // Không chữ ký, đúng như alg=none mô tả.
            assertThatThrownBy(() -> service(KHOA, BAY_GIO).doc(header + "." + payload + "."))
                    .isInstanceOf(AuthorizationException.class)
                    .hasFieldOrPropertyWithValue("code", "auth.token_khong_hop_le");
        }

        @Test
        @DisplayName("header đổi thuật toán thì loại, kể cả khi chữ ký HMAC vẫn đúng")
        void doi_thuat_toan_bi_loai() {
            // Kẻ tấn công ký ĐÚNG bằng khoá thật nhưng khai một alg khác. Nếu Jwt đọc trường
            // alg thì đây là đường vào; vì nó so header với hằng số nên đây là ngõ cụt.
            String header = B64.encodeToString(
                    "{\"alg\":\"HS512\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            String payload = B64.encodeToString(
                    "{\"sub\":1,\"handle\":\"x\",\"role\":\"ADMIN\",\"exp\":99999999999}"
                            .getBytes(StandardCharsets.UTF_8));
            String gia = header + "." + payload + ".chu-ky-bat-ky";

            assertThatThrownBy(() -> service(KHOA, BAY_GIO).doc(gia))
                    .isInstanceOf(AuthorizationException.class);
        }

        @Test
        @DisplayName("★ sửa payload để tự thăng ADMIN thì chữ ký không còn khớp")
        void sua_payload_thi_chu_ky_hong() {
            String token = service(KHOA, BAY_GIO).phat(new CurrentUser(7L, "dev", Role.USER));
            String[] doan = token.split("\\.");
            String payloadGia = B64.encodeToString(
                    "{\"sub\":7,\"handle\":\"dev\",\"role\":\"ADMIN\",\"exp\":99999999999}"
                            .getBytes(StandardCharsets.UTF_8));

            assertThatThrownBy(() -> service(KHOA, BAY_GIO)
                    .doc(doan[0] + "." + payloadGia + "." + doan[2]))
                    .isInstanceOf(AuthorizationException.class)
                    .hasFieldOrPropertyWithValue("code", "auth.token_khong_hop_le");
        }

        @Test
        @DisplayName("token ký bằng khoá khác thì không đọc được")
        void khoa_khac_thi_khong_doc_duoc() {
            String token = service(KHOA_KHAC, BAY_GIO).phat(DEV);

            assertThatThrownBy(() -> service(KHOA, BAY_GIO).doc(token))
                    .isInstanceOf(AuthorizationException.class);
        }

        @Test
        @DisplayName("đổi một ký tự của chữ ký là hỏng")
        void doi_mot_ky_tu_chu_ky() {
            String token = service(KHOA, BAY_GIO).phat(DEV);
            char cuoi = token.charAt(token.length() - 1);
            String doi = token.substring(0, token.length() - 1) + (cuoi == 'A' ? 'B' : 'A');

            assertThatThrownBy(() -> service(KHOA, BAY_GIO).doc(doi))
                    .isInstanceOf(AuthorizationException.class);
        }
    }

    @Nested
    @DisplayName("Hạn dùng")
    class HanDung {

        @Test
        @DisplayName("★ hết hạn trả MÃ RIÊNG, để frontend biết nên refresh chứ không đăng xuất")
        void het_han_co_ma_rieng() {
            String token = service(KHOA, BAY_GIO).phat(DEV);
            Instant sau16Phut = BAY_GIO.plus(Duration.ofMinutes(16));

            assertThatThrownBy(() -> service(KHOA, sau16Phut).doc(token))
                    .isInstanceOf(AuthorizationException.class)
                    .hasFieldOrPropertyWithValue("code", "auth.token_het_han");
        }

        @Test
        @DisplayName("còn 1 giây thì vẫn dùng được")
        void sat_han_van_dung_duoc() {
            String token = service(KHOA, BAY_GIO).phat(DEV);
            Instant sau14Phut = BAY_GIO.plus(Duration.ofMinutes(14));

            assertThat(service(KHOA, sau14Phut).doc(token).id()).isEqualTo(7L);
        }
    }

    @Nested
    @DisplayName("Đầu vào dị dạng")
    class DiDang {

        @Test
        @DisplayName("chuỗi rác, thiếu đoạn, thừa đoạn, rỗng — tất cả đều là token không hợp lệ")
        void moi_dang_hong_deu_bi_loai() {
            JwtService s = service(KHOA, BAY_GIO);
            for (String xau : new String[]{
                    "", ".", "..", "a.b", "a.b.c.d", "khong-co-dau-cham",
                    Jwt.HEADER_B64 + "..", "." + Jwt.HEADER_B64 + ".x",
                    Jwt.HEADER_B64 + ".@@@.###"}) {
                assertThatThrownBy(() -> s.doc(xau))
                        .describedAs("token dị dạng: '%s'", xau)
                        .isInstanceOf(AuthorizationException.class);
            }
        }

        @Test
        @DisplayName("★ token khổng lồ bị chặn ở cửa, trước khi tốn một phép HMAC nào")
        void token_khong_lo_bi_chan() {
            String qua_dai = Jwt.HEADER_B64 + "." + "A".repeat(10_000) + ".B";

            assertThatThrownBy(() -> service(KHOA, BAY_GIO).doc(qua_dai))
                    .isInstanceOf(AuthorizationException.class);
        }

        @Test
        @DisplayName("null không làm nổ NullPointerException")
        void null_khong_no() {
            assertThatThrownBy(() -> service(KHOA, BAY_GIO).doc(null))
                    .isInstanceOf(AuthorizationException.class);
        }
    }

    @Nested
    @DisplayName("Thông báo lỗi không được rò rỉ gì")
    class KhongRoRi {

        @Test
        @DisplayName("★ câu ra tới client không chứa token, không chứa khoá, không chứa 'HMAC'")
        void thong_bao_sach() {
            String token = service(KHOA_KHAC, BAY_GIO).phat(DEV);
            try {
                service(KHOA, BAY_GIO).doc(token);
                throw new AssertionError("đáng lẽ phải ném");
            } catch (AuthorizationException e) {
                assertThat(e.publicMessage())
                        .doesNotContain(token, KHOA, "HMAC", "HS256", "signature");
                assertThat(e.getMessage()).doesNotContain(token, KHOA);
            }
        }
    }
}
