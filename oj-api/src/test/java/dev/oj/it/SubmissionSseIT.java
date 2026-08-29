package dev.oj.it;

import dev.oj.contract.ClaimRequestDto;
import dev.oj.contract.JudgeProgressDto;
import dev.oj.contract.JudgeResultDto;
import dev.oj.contract.Verdict;
import dev.oj.judging.application.usecase.ClaimJudgeJobUseCase;
import dev.oj.judging.application.usecase.RecordJudgeProgressUseCase;
import dev.oj.judging.application.usecase.RecordJudgeResultUseCase;
import dev.oj.judging.application.usecase.SubmitSolutionUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

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

/**
 * ★ Bước 3.8 · 3.9 · 3.10 · FR-SUB-05 — luồng verdict realtime, <b>qua HTTP thật và Redis thật</b>.
 *
 * <h2>Vì sao ca này không thay được bằng unit test</h2>
 * Đường realtime có bốn mảnh, và mỗi mảnh nằm ở một chỗ khác nhau: use-case publish
 * <i>sau commit</i>, Redis chuyển thông điệp <b>qua một tiến trình khác</b>,
 * {@code RedisMessageListenerContainer} deserialize nó, {@code SseEmitter} đẩy ra socket.
 * Một bus giả trong cùng JVM làm cả bốn mảnh biến mất — và test sẽ xanh cho cả một hiện thực
 * giữ danh sách kết nối trong bộ nhớ, đúng thứ ADR 011 nói phải tránh.
 *
 * <p>Kiểm bằng tay thì làm được, và tôi đã làm. Nhưng kiểm tay không giữ được gì cho lần sau.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SubmissionSseIT extends PostgresIT {

    /** Rộng rãi: một chuyến đi vòng qua Redis mất mili giây, nhưng CI thì không hứa gì. */
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
            // Sự kiện ĐẦU TIÊN luôn là trạng thái hiện tại — đó là thứ làm việc mở lại luồng
            // (Bước 3.10) tự vá mọi khoảng trống.
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

    /**
     * ★ Bất biến #1 ở đầu ra cuối cùng. Luồng SSE đi thẳng ra trình duyệt mà <b>không</b> qua
     * bộ lọc {@code feedback_level} — nên nó không được mang thứ bộ lọc ấy có thể cấm.
     */
    @Test
    @DisplayName("★ SEC3 — không byte nào trên luồng nói test nào sai")
    void luong_khong_mang_chi_tiet_test() throws Exception {
        long id = submit();
        try (Stream stream = open(id)) {
            stream.awaitLineContaining("QUEUED");
            int attempt = claim(id);

            // Bài SAI ở test 2 — con số đó có thật, được lưu, và không được lên luồng.
            recordResult.record(new JudgeResultDto(id, attempt, Verdict.WA, 0, 100,
                    2, 2, 99, 4096, "log giả", "SG signal=11",
                    "mac-m1max-host", new BigDecimal("1.000"), Instant.now(), List.of()));
            stream.awaitLineContaining("DONE");

            assertThat(stream.everything())
                    .as("chi tiết đi qua GET /submissions/{id}, nơi có FeedbackPolicy")
                    .doesNotContain("failedTestOrdinal", "compileLog", "isolate");
        }
    }

    /**
     * Trường hợp phổ biến nhất: mở lại link một bài nộp cũ. Không được giữ một kết nối 5 phút
     * để chờ một sự kiện sẽ không bao giờ tới.
     */
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

    // ---------------------------------------------------------------------

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

    /**
     * ★ Lỗi trên đường SSE phải là JSON đọc được, không phải 500 rỗng.
     *
     * <h2>Lỗi đã gặp thật khi chạy tay ở Bước 4.12</h2>
     * Endpoint này khai {@code produces = text/event-stream}. Khi nó ném lỗi <i>trước khi</i>
     * ghi byte nào, Spring đã đặt sẵn {@code Content-Type} theo khai báo đó rồi không tìm được
     * bộ chuyển đổi nào ghi {@code ApiError} thành {@code text/event-stream} — kết quả là
     * <b>500 với thân rỗng</b> thay cho 401 hoặc 404.
     *
     * <p>Chỉ hiện ra khi client gửi {@code Accept: text/event-stream}, tức là đúng cách trình
     * duyệt gọi và <b>không</b> phải cách một lệnh {@code curl} thông thường gọi. Ca này cố
     * định hành vi đúng.
     *
     * <p>Hậu quả nếu để nguyên: {@code js/api.js} phân biệt "token hết hạn, làm mới rồi thử
     * lại" với "hỏng thật" bằng mã lỗi trong thân phản hồi. Một 500 rỗng xoá mất sự phân biệt
     * ấy, và người dùng có token hết hạn giữa lúc theo dõi bài nộp thấy luồng chết vĩnh viễn.
     */
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

    private Stream open(long submissionId) throws Exception {
        return new Stream(URI.create(
                "http://localhost:" + port + "/api/v1/submissions/" + submissionId + "/stream"),
                bearerDev());
    }

    /**
     * Đọc SSE trên một luồng riêng.
     *
     * <p>{@code BodyHandlers.ofLines()} trả về một {@code Stream} <b>lười</b>: nó chỉ chặn khi
     * bị duyệt. Duyệt nó trên luồng chính thì test đứng ngay tại đó và không bao giờ đẩy được
     * verdict — nên việc đọc phải nằm ở một luồng khác, và luồng chính chỉ chờ.
     */
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

        /** Máy chủ chủ động đóng khi bài đã xong — client không phải chờ hết timeout. */
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
