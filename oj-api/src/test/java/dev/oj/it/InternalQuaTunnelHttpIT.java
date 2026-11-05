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

class InternalQuaTunnelHttpIT extends HttpIT {

    private static final List<String> BIEN_THE = List.of(
            JudgeEndpoints.BASE + "/claim",
            "/" + JudgeEndpoints.BASE + "/claim",
            "/." + JudgeEndpoints.BASE + "/claim",
            "/api/v1/../.." + JudgeEndpoints.BASE + "/claim",
            "/internal;x=1/judge/claim",
            "/%2e" + JudgeEndpoints.BASE + "/claim",
            "/api/v1/%2e%2e/%2e%2e" + JudgeEndpoints.BASE + "/claim");

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
