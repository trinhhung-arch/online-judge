package dev.oj.it;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BaoMatHttpIT extends HttpIT {

    private static final String KE_XAU = "https://ke-xau.example";

    private final ListAppender<ILoggingEvent> nghe = new ListAppender<>();

    @org.springframework.beans.factory.annotation.Value("${oj.request-body-max}")
    org.springframework.util.unit.DataSize tranThan;

    @BeforeEach
    void ganTai() {
        nghe.start();
        goc().addAppender(nghe);
    }

    @AfterEach
    void goTai() {
        goc().detachAppender(nghe);
    }

    @Test
    @DisplayName("★ BM-1 · languageCode chứa xuống dòng + một dòng log tự bịa → 400, và không dòng log nào bị tách đôi")
    void ma_ngon_ngu_khong_chen_duoc_dong_log() {
        String giaMao = "cpp20\n00:00:00.000 ERROR [no-trace] dev.oj.GIA_MAO : admin da xoa het bai nop";

        var res = goi(http.post().uri("/api/v1/submissions")
                .header("Authorization", "Bearer " + tokenCua("dev"))
                .body(Map.of("problemId", PROBLEM_ID, "languageCode", giaMao,
                        "source", "int main(){}")));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).as("bị chặn ở cửa nhận request, trước khi tới use-case")
                .containsEntry("code", "request.invalid")
                .extractingByKey("details").asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsKey("languageCode");
        assertThat(jdbc.sql("SELECT count(*) FROM submissions").query(Integer.class).single())
                .isZero();
        assertThat(nghe.list).isNotEmpty().allSatisfy(e -> assertThat(dongLog(e))
                .as("mỗi sự kiện log phải ra đúng MỘT dòng — dòng thứ hai là dòng người lạ viết")
                .containsOnlyOnce("\n")
                .doesNotContain("\nGIA_MAO", "\n00:00:00.000"));
    }

    @Test
    @DisplayName("★ BM-1 · gốc rễ: mọi thông điệp có xuống dòng đều bị ép thành một dòng — kể cả từ chỗ chưa ai nghĩ tới")
    void mau_log_ep_moi_thong_diep_thanh_mot_dong() {
        LoggerFactory.getLogger("dev.oj.thu").warn("trước\r\n12:00:00.000 ERROR [x] gia : sau\rcuối");

        assertThat(nghe.list).singleElement().satisfies(e -> assertThat(dongLog(e))
                .containsOnlyOnce("\n")
                .endsWith("trước ⏎ 12:00:00.000 ERROR [x] gia : sau ⏎ cuối\n"));
    }

    @Test
    @DisplayName("★ BM-8 · đăng ký kèm \"role\":\"ADMIN\" và các trường lạ → tài khoản vẫn là USER, ACTIVE")
    void dang_ky_khong_tu_gan_vai_tro() {
        String matKhau = "mk-" + java.util.UUID.randomUUID();
        var dangKy = goi(http.post().uri("/api/v1/auth/register")
                .body(Map.of("handle", "tu-phong-admin", "email", "tu-phong@oj.test",
                        "displayName", "Tự phong", "password", matKhau,
                        "role", "ADMIN", "status", "ACTIVE", "emailVerifiedAt", "2026-01-01T00:00:00Z",
                        "id", 1)));
        assertThat(dangKy.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertThat(jdbc.sql("""
                SELECT role || ' ' || status || ' ' || (email_verified_at IS NULL) || ' ' || (id <> 1)
                  FROM users WHERE handle = 'tu-phong-admin'
                """).query(String.class).single()).isEqualTo("USER ACTIVE true true");
        assertThat(login("tu-phong-admin", matKhau).getBody())
                .containsEntry("role", "USER");
    }

    @Test
    @DisplayName("★ BM-6 · trang lạ gọi API bằng trình duyệt của người dùng: không có header CORS nào cho phép đọc kết quả")
    void khong_mo_cors_cho_nguon_la() {
        var tienKiem = http.method(HttpMethod.OPTIONS).uri("/api/v1/submissions")
                .header("Origin", KE_XAU)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization, content-type")
                .exchange((req, r) -> r.getHeaders(), false);
        var thatSu = http.get().uri("/api/v1/me")
                .header("Origin", KE_XAU)
                .header("Authorization", "Bearer " + tokenCua("dev"))
                .exchange((req, r) -> r.getHeaders(), false);

        for (var headers : List.of(tienKiem, thatSu)) {
            assertThat(headers.getFirst("Access-Control-Allow-Origin"))
                    .as("thêm cấu hình CORS là việc phải quyết có chủ ý").isNull();
            assertThat(headers.getFirst("Access-Control-Allow-Credentials")).isNull();
        }
    }

    @Test
    @DisplayName("★ BM-9 · thân JSON 8 MB khai Content-Length → 413 trước khi đọc một byte; thân chunked vượt trần → 413 khi chạm trần")
    void than_json_qua_lon_bi_chan_som() throws Exception {
        int tran = (int) tranThan.toBytes();
        var coDoDai = gui(HttpRequest.BodyPublishers.ofByteArray(thanNopBai(8 << 20)));
        byte[] chunked = thanNopBai(tran + (64 << 10));
        var khongDoDai = gui(HttpRequest.BodyPublishers.ofInputStream(() ->
                new java.io.ByteArrayInputStream(chunked)));

        for (var res : List.of(coDoDai, khongDoDai)) {
            assertThat(res.statusCode()).as("thân: %s", res.body()).isEqualTo(413);
            assertThat(res.body()).contains("\"code\":\"request.body_too_large\"");
        }
        assertThat(jdbc.sql("SELECT count(*) FROM submissions").query(Integer.class).single())
                .isZero();
    }

    @Test
    @DisplayName("BM-9 · thân dưới trần vẫn tới được use-case — mã nguồn 70 KB nhận đúng lỗi 64 KB, không phải 413")
    void than_duoi_tran_van_toi_use_case() throws Exception {
        var res = gui(HttpRequest.BodyPublishers.ofByteArray(thanNopBai(70 << 10)));

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("submission.source_too_large");
    }

    private HttpResponse<String> gui(HttpRequest.BodyPublisher noiDung) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/submissions"))
                .header("Authorization", "Bearer " + tokenCua("dev"))
                .header("Content-Type", "application/json")
                .POST(noiDung).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static byte[] thanNopBai(int soByteNguon) {
        return ("{\"problemId\":1,\"languageCode\":\"cpp20\",\"source\":\""
                + "a".repeat(soByteNguon) + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    private static Logger goc() {
        return (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    }

    @SuppressWarnings("unchecked")
    private static String dongLog(ILoggingEvent e) {
        var console = (ConsoleAppender<ILoggingEvent>) goc().getAppender("CONSOLE");
        assertThat(console).as("logback-spring.xml phải có appender CONSOLE").isNotNull();
        return new String(console.getEncoder().encode(e), StandardCharsets.UTF_8);
    }
}
