package dev.oj.it;

import dev.oj.judging.application.usecase.SubmitSolutionUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"oj.sse.timeout=2s", "oj.sse.heartbeat=300ms",
                "oj.datasource.app.maximum-pool-size=2", "oj.datasource.judge.maximum-pool-size=2"})
@DirtiesContext
class LuongSseDonDepIT extends PostgresIT {

    @LocalServerPort int port;

    @Autowired SubmitSolutionUseCase submitSolution;

    @Test
    @DisplayName("★ TD-07 · luồng sống quá hạn → server đóng luồng và gỡ đăng ký Redis")
    void het_han_thi_go_dang_ky() throws Exception {
        long id = nop();
        try (var client = HttpClient.newHttpClient()) {
            var res = moLuong(client, id);
            choToi(() -> soNguoiNghe(id) == 1, "đăng ký kênh Redis khi mở luồng");

            try (InputStream than = res.body()) {
                assertThat(than.readAllBytes()).as("server tự đóng luồng sau oj.sse.timeout").isNotEmpty();
            }
            choToi(() -> soNguoiNghe(id) == 0, "gỡ đăng ký kênh Redis sau khi luồng hết hạn");
        }
    }

    @Test
    @DisplayName("★ TD-07 · client ngắt giữa chừng → nhịp tim phát hiện, server gỡ đăng ký Redis")
    void client_ngat_thi_go_dang_ky() throws Exception {
        long id = nop();
        var client = HttpClient.newHttpClient();
        var res = moLuong(client, id);
        choToi(() -> soNguoiNghe(id) == 1, "đăng ký kênh Redis khi mở luồng");

        res.body().close();
        client.shutdownNow();

        choToi(() -> soNguoiNghe(id) == 0,
                "1000 kết nối SSE mà mỗi lần đứt để lại một đăng ký là rò rỉ không ai thấy");
    }

    private long nop() {
        return submitSolution.submit(new SubmitSolutionUseCase.Command(
                PROBLEM_ID, "cpp20", "// EXPECT: AC\nint main(){}")).submissionId();
    }

    private HttpResponse<InputStream> moLuong(HttpClient client, long id) throws Exception {
        var res = client.send(HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + port + "/api/v1/submissions/" + id + "/stream"))
                .header("Authorization", bearerDev()).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(res.statusCode()).isEqualTo(200);
        return res;
    }

    private static long soNguoiNghe(long id) {
        try {
            String ra = REDIS.execInContainer("redis-cli", "PUBSUB", "NUMSUB",
                    "oj:submission:" + id).getStdout().trim();
            String[] dong = ra.split("\\R");
            return Long.parseLong(dong[dong.length - 1].trim());
        } catch (Exception e) {
            throw new AssertionError("không đọc được PUBSUB NUMSUB", e);
        }
    }

    private static void choToi(BooleanSupplier dieuKien, String moTa) throws InterruptedException {
        long hetHan = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!dieuKien.getAsBoolean()) {
            assertThat(System.nanoTime()).as("hết 10 s mà chưa thấy: %s", moTa).isLessThan(hetHan);
            Thread.sleep(100);
        }
    }
}
