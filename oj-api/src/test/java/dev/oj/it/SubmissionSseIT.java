package dev.oj.it;

import dev.oj.contract.ClaimRequestDto;
import dev.oj.contract.JudgeProgressDto;
import dev.oj.contract.JudgeResultDto;
import dev.oj.contract.Verdict;
import dev.oj.judging.application.usecase.ClaimJudgeJobUseCase;
import dev.oj.judging.application.usecase.RecordJudgeProgressUseCase;
import dev.oj.judging.application.usecase.RecordJudgeResultUseCase;
import dev.oj.judging.application.usecase.SubmitSolutionUseCase;
import dev.oj.platform.security.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.testcontainers.DockerClientFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SubmissionSseIT extends PostgresIT {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @LocalServerPort int port;

    @Autowired SubmitSolutionUseCase submitSolution;
    @Autowired ClaimJudgeJobUseCase claimJudgeJob;
    @Autowired RecordJudgeProgressUseCase recordProgress;
    @Autowired RecordJudgeResultUseCase recordResult;

    @Test
    @DisplayName("★ verdict đi từ /internal/judge/result qua Redis tới trình duyệt")
    void verdict_toi_duoc_trinh_duyet_qua_redis() throws Exception {
        long id = submit();
        try (Stream stream = open(id)) {
            stream.awaitLineContaining("QUEUED");

            int attempt = claim(id);
            recordProgress.record(new JudgeProgressDto(id, attempt, 1, 2, 3,
                    List.of(new JudgeProgressDto.TestOutcome(1, Verdict.AC, 12, 2048))));
            stream.awaitLineContaining("JUDGING");

            recordResult.record(accepted(id, attempt));
            String terminal = stream.awaitLineContaining("DONE");

            assertThat(terminal).contains("\"verdict\":\"AC\"", "\"terminal\":true");
        }
    }

    @Test
    @DisplayName("★ SEC3 — không byte nào trên luồng nói test nào sai")
    void luong_khong_mang_chi_tiet_test() throws Exception {
        long id = submit();
        try (Stream stream = open(id)) {
            stream.awaitLineContaining("QUEUED");
            int attempt = claim(id);

            recordResult.record(new JudgeResultDto(id, attempt, Verdict.WA, 0, 100,
                    2, 2, 99, 4096, "log giả", "SG signal=11",
                    "mac-m1max-host", new BigDecimal("1.000"), Instant.now(), List.of()));
            stream.awaitLineContaining("DONE");

            assertThat(stream.everything())
                    .as("chi tiết đi qua GET /submissions/{id}, nơi có FeedbackPolicy")
                    .doesNotContain("failedTestOrdinal", "compileLog", "isolate");
        }
    }

    @Test
    @DisplayName("bài đã chấm xong thì luồng gửi một sự kiện rồi đóng ngay")
    void bai_da_xong_thi_dong_ngay() throws Exception {
        long id = submit();
        recordResult.record(accepted(id, claim(id)));

        try (Stream stream = open(id)) {
            assertThat(stream.awaitLineContaining("DONE")).contains("\"terminal\":true");
            stream.awaitClosed();
        }
    }

    private long submit() {
        return submitSolution.submit(new SubmitSolutionUseCase.Command(
                PROBLEM_ID, "cpp20", "// EXPECT: AC\nint main(){}")).submissionId();
    }

    private int claim(long expectedId) {
        var job = claimJudgeJob.claim(ClaimRequestDto.single("mac-m1max-host", "arm64"))
                .orElseThrow(() -> new AssertionError("hàng đợi rỗng"));
        assertThat(job.submissionId()).isEqualTo(expectedId);
        return job.attempt();
    }

    private static JudgeResultDto accepted(long id, int attempt) {
        return new JudgeResultDto(id, attempt, Verdict.AC, 100, 100, null, 3, 234, 4096,
                null, "OK", "mac-m1max-host", new BigDecimal("1.000"), Instant.now(), List.of());
    }

    @Test
    @DisplayName("★ lỗi trên đường SSE trả JSON có mã, không phải 500 rỗng")
    void loi_tren_duong_sse_van_la_json() throws Exception {
        var client = HttpClient.newHttpClient();
        var url = URI.create("http://localhost:" + port + "/api/v1/submissions/999999/stream");

        for (String[] ca : new String[][]{
                {"không token", null, "401"},
                {"bài không tồn tại", bearerDev(), "404"}}) {
            var req = HttpRequest.newBuilder(url).header("Accept", "text/event-stream");
            if (ca[1] != null) {
                req.header("Authorization", ca[1]);
            }
            var res = client.send(req.GET().build(), HttpResponse.BodyHandlers.ofString());

            assertThat(res.statusCode())
                    .describedAs("ca '%s'", ca[0]).isEqualTo(Integer.parseInt(ca[2]));
            assertThat(res.body())
                    .describedAs("ca '%s' — thân rỗng thì frontend không phân biệt được lỗi gì", ca[0])
                    .contains("\"code\"");
        }
    }

    @Test
    @DisplayName("★ TD-05 · đề NONE: luồng không mang con số tiến độ nào — không đoán được bài sai ở test 23")
    void de_none_luong_khong_lo_so_test() throws Exception {
        jdbc.sql("UPDATE problems SET feedback_level = 'NONE' WHERE id = :id")
                .param("id", PROBLEM_ID).update();
        long id = submit();
        try (Stream stream = open(id)) {
            stream.awaitLineContaining("QUEUED");
            int attempt = claim(id);
            recordProgress.record(new JudgeProgressDto(id, attempt, 1, 20, 50,
                    List.of(new JudgeProgressDto.TestOutcome(20, Verdict.AC, 12, 2048))));
            recordProgress.record(new JudgeProgressDto(id, attempt, 21, 23, 50,
                    List.of(new JudgeProgressDto.TestOutcome(23, Verdict.WA, 12, 2048))));
            recordResult.record(new JudgeResultDto(id, attempt, Verdict.WA, 0, 100, 23, 23, 12,
                    2048, null, null, "mac-m1max-host", new BigDecimal("1.000"), Instant.now(),
                    List.of()));
            stream.awaitLineContaining("DONE");

            assertThat(stream.everything())
                    .contains("JUDGING")
                    .doesNotContain("\"totalTests\":50", "\"testsDone\":20", "\"testsDone\":23");
        }
    }

    @Test
    @DisplayName("★ TD-03 · mở luồng của bài CÓ THẬT nhưng của người khác → 404 cùng mã với bài không tồn tại")
    void luong_bai_cua_nguoi_khac_la_404() throws Exception {
        long id = submit();
        var client = HttpClient.newHttpClient();

        var res = client.send(HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + port + "/api/v1/submissions/" + id + "/stream"))
                .header("Accept", "text/event-stream")
                .header("Authorization", bearer(SETTER_ID, "setter", Role.SETTER))
                .GET().build(), HttpResponse.BodyHandlers.ofString());

        assertThat(res.statusCode()).as("403 sẽ xác nhận bài này tồn tại").isEqualTo(404);
        assertThat(res.body()).contains("\"submission.not_found\"");
    }

    @Test
    @DisplayName("★ SC-01 · Redis chết khi đang mở trang bài nộp: ghi verdict không hỏng, REST vẫn trả đúng")
    void redis_chet_khi_dang_xem_bai() throws Exception {
        long id = submit();
        try (Stream stream = open(id)) {
            stream.awaitLineContaining("QUEUED");
            int attempt = claim(id);

            DockerClientFactory.instance().client().pauseContainerCmd(REDIS.getContainerId()).exec();
            try {
                assertThat(recordResult.record(accepted(id, attempt)))
                        .as("Redis chỉ là đường đẩy sự kiện — nó chết không được làm mất verdict")
                        .isEqualTo(RecordJudgeResultUseCase.Outcome.RECORDED);
            } finally {
                DockerClientFactory.instance().client().unpauseContainerCmd(REDIS.getContainerId()).exec();
            }

            var res = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(
                            "http://localhost:" + port + "/api/v1/submissions/" + id))
                    .header("Authorization", bearerDev()).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(res.statusCode()).isEqualTo(200);
            assertThat(res.body()).as("đường polling REST mà trang lùi về khi SSE im lặng")
                    .contains("\"status\":\"DONE\"", "\"verdict\":\"AC\"");
        }
    }

    private Stream open(long submissionId) throws Exception {
        return new Stream(URI.create(
                "http://localhost:" + port + "/api/v1/submissions/" + submissionId + "/stream"),
                bearerDev());
    }

    private static final class Stream implements AutoCloseable {

        private final HttpClient client = HttpClient.newHttpClient();
        private final List<String> lines = new CopyOnWriteArrayList<>();
        private final Thread reader;
        private volatile boolean closed;

        Stream(URI uri, String bearer) throws IOException, InterruptedException {
            HttpResponse<java.util.stream.Stream<String>> response = client.send(
                    HttpRequest.newBuilder(uri).header("Authorization", bearer).GET().build(),
                    HttpResponse.BodyHandlers.ofLines());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Cache-Control"))
                    .as("luồng SSE commit response sớm — KhongLuuDemApiFilter phải đặt header trước chain")
                    .hasValue("no-store");

            reader = Thread.ofPlatform().daemon().start(() -> {
                try (var body = response.body()) {
                    body.forEach(lines::add);
                } finally {
                    closed = true;
                }
            });
        }

        String awaitLineContaining(String needle) {
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (System.nanoTime() < deadline) {
                for (String line : lines) {
                    if (line.contains(needle)) {
                        return line;
                    }
                }
                sleep();
            }
            throw new AssertionError(
                    "Không thấy sự kiện chứa '" + needle + "' sau " + WAIT + ".\nĐã nhận:\n"
                            + String.join("\n", lines));
        }

        void awaitClosed() {
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (System.nanoTime() < deadline && !closed) {
                sleep();
            }
            assertThat(closed)
                    .as("bài đã DONE mà luồng vẫn mở là giữ một kết nối 5 phút cho một sự kiện "
                            + "sẽ không bao giờ tới")
                    .isTrue();
        }

        String everything() {
            return String.join("\n", lines);
        }

        private static void sleep() {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }

        @Override
        public void close() {
            reader.interrupt();
            client.close();
        }
    }
}
