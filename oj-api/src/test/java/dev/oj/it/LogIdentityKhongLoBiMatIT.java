package dev.oj.it;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ Bất biến #9 trên log THẬT, cho mọi luồng còn lại của {@code identity}. Thiết kế:
 * {@code Claude outputs/identity-test-cases.md}, ca RR-03.
 *
 * <p>{@link LogKhongLoBiMatIT} mới soi một lượt đăng nhập sai. Ở đây là đăng ký, đổi mật
 * khẩu, làm mới / dùng lại / đăng xuất refresh token, gõ mã xác minh email — và một thân JSON
 * hỏng, vì nhánh lỗi là chỗ dễ lọt nhất: dòng log của nó do framework soạn, không phải do ta.
 * Cùng cách đo với lớp kia: gắn {@link ListAppender} vào logger gốc, hạ {@code dev.oj} xuống
 * TRACE, rồi tìm từng bí mật trong mọi dòng — kể cả stack trace.
 */
class LogIdentityKhongLoBiMatIT extends HttpIT {

    private static final String MOC = "MOC_BI_MAT_3a9d2e";

    private final ListAppender<ILoggingEvent> nghe = new ListAppender<>();
    private Level mucCu;

    @BeforeEach
    void ganTai() {
        Logger oj = (Logger) LoggerFactory.getLogger("dev.oj");
        mucCu = oj.getLevel();
        oj.setLevel(Level.TRACE);
        nghe.start();
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).addAppender(nghe);
    }

    @AfterEach
    void goTai() {
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).detachAppender(nghe);
        ((Logger) LoggerFactory.getLogger("dev.oj")).setLevel(mucCu);
    }

    @Test
    @DisplayName("★ đăng ký thành công rồi đăng ký trùng: mật khẩu không vào dòng log nào")
    void dang_ky_khong_ghi_mat_khau() {
        Map<String, Object> than = Map.of("handle", "log-moc", "email", "log-moc@oj.test",
                "displayName", "Log", "password", "mk-" + MOC);

        assertThat(goi(http.post().uri("/api/v1/auth/register").body(than)).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(goi(http.post().uri("/api/v1/auth/register").body(than)).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        khongLo(MOC);
    }

    @Test
    @DisplayName("★ đổi mật khẩu — sai mật khẩu cũ, rồi đổi thật: cả mật khẩu cũ lẫn mới không vào log")
    void doi_mat_khau_khong_ghi_mat_khau() {
        String dev = "Bearer " + tokenCua("dev");

        assertThat(goi(http.post().uri("/api/v1/me/password").header("Authorization", dev)
                .body(Map.of("matKhauCu", "cu-" + MOC, "matKhauMoi", "moi-" + MOC)))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(goi(http.post().uri("/api/v1/me/password").header("Authorization", dev)
                .body(Map.of("matKhauCu", MAT_KHAU_DEV, "matKhauMoi", "moi-" + MOC)))
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        khongLo(MOC);
    }

    @Test
    @DisplayName("★ làm mới, dùng lại token cũ (bị coi là trộm), đăng xuất, token bịa: không giá trị refresh token nào vào log")
    void refresh_token_khong_vao_log() {
        String r1 = (String) login("dev", MAT_KHAU_DEV).getBody().get("refreshToken");
        var lan1 = goi(http.post().uri("/api/v1/auth/refresh").body(Map.of("refreshToken", r1)));
        assertThat(lan1.getStatusCode()).isEqualTo(HttpStatus.OK);
        String r2 = (String) lan1.getBody().get("refreshToken");

        assertThat(goi(http.post().uri("/api/v1/auth/refresh").body(Map.of("refreshToken", r1)))
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(goi(http.post().uri("/api/v1/auth/logout").body(Map.of("refreshToken", r2)))
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(goi(http.post().uri("/api/v1/auth/refresh").body(Map.of("refreshToken", MOC)))
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        khongLo(r1, r2, MOC);
    }

    @Test
    @DisplayName("★ gõ sai mã xác minh email: mã đã gõ không vào log")
    void ma_xac_minh_email_khong_vao_log() {
        String dev = "Bearer " + tokenCua("dev");

        assertThat(goi(http.post().uri("/api/v1/me/xac-minh-email/xac-nhan")
                .header("Authorization", dev).body(Map.of("ma", MOC)))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        khongLo(MOC);
    }

    /**
     * ★ JSON hỏng mang một mật khẩu không có ngoặc kép — kiểu lỗi của một client viết tay hay
     * một lệnh {@code curl} gõ vội. Bộ phân tích JSON chép token lạ vào câu lỗi của nó
     * ({@code Unrecognized token '…'}), nên bất cứ nơi nào ghi câu lỗi ấy ra log là ghi luôn
     * mật khẩu.
     */
    @Test
    @DisplayName("★ JSON hỏng: mật khẩu viết không ngoặc kép không được lọt vào log qua câu lỗi của bộ phân tích JSON")
    void json_hong_khong_lo_mat_khau() {
        var res = goi(http.post().uri("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .body("{\"dinhDanh\":\"dev\",\"password\":" + MOC + "}"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        khongLo(MOC);
    }

    private void khongLo(String... biMat) {
        assertThat(nghe.list)
                .as("không bắt được dòng log nào — appender không gắn được, ca này không đo gì")
                .isNotEmpty();
        for (ILoggingEvent e : nghe.list) {
            String toan = e.getFormattedMessage() + (e.getThrowableProxy() == null ? ""
                    : ThrowableProxyUtil.asString(e.getThrowableProxy()));
            for (String s : biMat) {
                assertThat(toan).as("[%s] %s", e.getLevel(), e.getLoggerName()).doesNotContain(s);
            }
        }
    }
}
