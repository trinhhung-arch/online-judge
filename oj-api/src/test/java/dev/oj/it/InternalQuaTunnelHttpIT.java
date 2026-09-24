package dev.oj.it;

import dev.oj.contract.JudgeEndpoints;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ {@code /internal} từ chối mọi request đi qua Cloudflare — ở MỌI dạng đường dẫn mà Tomcat
 * chuẩn hoá về {@code /internal/judge/*}.
 *
 * <h2>Lỗ đã có thật (rà bảo mật 2026-09-23)</h2>
 * Luật ingress {@code ^/internal(/|$)} của cloudflared không khớp {@code //internal/…},
 * {@code /./internal/…}, {@code /internal;x=1/…}, {@code /%2e/internal/…} — chúng đi thẳng tới
 * {@code localhost:8080}, và Tomcat đưa tất cả về {@code InternalSecretFilter}. Lớp mạng thủng,
 * chỉ còn shared secret.
 * {@code kiem-tunnel.sh} không thấy vì nó chỉ thử đường dẫn chuẩn.
 *
 * <h2>Hai lời gọi cho mỗi biến thể, và lời gọi thứ nhất KHÔNG phải trang trí</h2>
 * <ol>
 *   <li><b>Không dấu Cloudflare, không secret → 401.</b> Chứng minh biến thể ấy TỚI ĐƯỢC
 *       filter. Thiếu nó, một biến thể mà Tomcat trả 400 hay 404 vì lý do khác sẽ làm lời gọi
 *       thứ hai xanh mà không đo gì.</li>
 *   <li><b>Dấu Cloudflare + ĐÚNG secret → 404.</b> Lộ secret không còn đủ.</li>
 * </ol>
 *
 * <p>Dùng {@link HttpClient} của JDK chứ không phải {@code RestClient}: phải gửi đường dẫn
 * NGUYÊN VĂN. Một client "tiện" chuẩn hoá {@code /./} hay gộp {@code //} trước khi gửi là
 * đo một thứ khác thứ kẻ tấn công gửi.
 */
class InternalQuaTunnelHttpIT extends HttpIT {

    /** Các dạng đường dẫn tới {@code claim} — cùng bộ với {@code scripts/kiem-tunnel.sh}. */
    private static final List<String> BIEN_THE = List.of(
            JudgeEndpoints.BASE + "/claim",
            "/" + JudgeEndpoints.BASE + "/claim",
            "/." + JudgeEndpoints.BASE + "/claim",
            "/api/v1/../.." + JudgeEndpoints.BASE + "/claim",
            "/internal;x=1/judge/claim",
            "/%2e" + JudgeEndpoints.BASE + "/claim",
            "/api/v1/%2e%2e/%2e%2e" + JudgeEndpoints.BASE + "/claim");

    /** {@code PostgresIT} đặt {@code oj.internal.shared-secret} bằng chuỗi này. */
    private static final String SECRET = "x".repeat(32);

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    @DisplayName("★★ mọi biến thể đường dẫn: tới được filter (401), và qua Cloudflare thì 404 dù đúng secret")
    void moi_bien_the_qua_cloudflare_deu_404() throws Exception {
        for (String duong : BIEN_THE) {
            assertThat(goi(duong, null, null))
                    .as("%s phải TỚI InternalSecretFilter — không thì ca dưới xanh vô nghĩa", duong)
                    .isEqualTo(401);
            assertThat(goi(duong, SECRET, "CF-Connecting-IP"))
                    .as("%s qua Cloudflare, mang đúng secret", duong)
                    .isEqualTo(404);
        }
    }

    @Test
    @DisplayName("mỗi header Cloudflare tự nó đủ để bị chặn")
    void moi_header_cloudflare_deu_chan() throws Exception {
        for (String dau : List.of("CF-Ray", "CF-Connecting-IP", "CDN-Loop")) {
            assertThat(goi(JudgeEndpoints.BASE + "/claim", SECRET, dau)).as(dau).isEqualTo(404);
        }
    }

    @Test
    @DisplayName("worker — đúng secret, không qua Cloudflare — vẫn qua được filter")
    void worker_van_qua_duoc() throws Exception {
        assertThat(goi(JudgeEndpoints.BASE + "/claim", SECRET, null))
                .as("đã qua filter: controller trả gì cũng được, miễn không phải 401/404")
                .isNotIn(401, 404);
    }

    /** @return mã HTTP. {@code dauCloudflare} là TÊN header, giá trị luôn là một IP hợp lệ. */
    private int goi(String duong, String secret, String dauCloudflare)
            throws IOException, InterruptedException {
        var yeuCau = HttpRequest.newBuilder(URI.create("http://localhost:" + port + duong))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"));
        if (secret != null) {
            yeuCau.header(JudgeEndpoints.SECRET_HEADER, secret);
        }
        if (dauCloudflare != null) {
            yeuCau.header(dauCloudflare, "203.0.113.7");
        }
        return client.send(yeuCau.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
