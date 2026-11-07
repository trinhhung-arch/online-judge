package dev.oj.worker.client;

import dev.oj.contract.JudgeResultDto;
import dev.oj.contract.Verdict;
import dev.oj.worker.config.WorkerProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ResultBufferTest {

    @Test
    @DisplayName("★ API lỗi tạm thời (5xx) → giữ lại và thử lại tới khi được, KHÔNG mất")
    void loi_tam_thoi_thi_giu_lai_va_thu_lai() throws Exception {
        var api = new FakeApi();
        api.transientFailures.set(3);

        try (var buffer = new ResultBuffer(api, properties())) {
            buffer.submit(result(1L, Verdict.AC));

            waitUntil(() -> api.sent.size() == 1);
            assertThat(api.sent).extracting(JudgeResultDto::submissionId).containsExactly(1L);
            assertThat(api.attempts).hasValue(4);
            assertThat(buffer.pendingCount()).isZero();
        }
    }

    @Test
    @DisplayName("★ API từ chối vĩnh viễn (4xx) → bỏ bản ghi đó, KHÔNG lặp vô hạn")
    void loi_vinh_vien_thi_khong_ket_hang_doi() throws Exception {
        var api = new FakeApi();
        api.permanentFailure = true;

        try (var buffer = new ResultBuffer(api, properties())) {
            buffer.submit(result(1L, Verdict.WA));
            buffer.submit(result(2L, Verdict.AC));

            waitUntil(() -> buffer.pendingCount() == 0 && api.attempts.get() >= 2);
            assertThat(api.sent).isEmpty();
        }
    }

    @Test
    void nhieu_ket_qua_deu_toi_dich_theo_thu_tu_bat_ky() throws Exception {
        var api = new FakeApi();

        try (var buffer = new ResultBuffer(api, properties())) {
            for (long id = 1; id <= 5; id++) {
                buffer.submit(result(id, Verdict.AC));
            }

            waitUntil(() -> api.sent.size() == 5);
            assertThat(api.sent).extracting(JudgeResultDto::submissionId)
                    .containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L);
        }
    }

    private static class FakeApi extends JudgeApiClient {

        final List<JudgeResultDto> sent = new CopyOnWriteArrayList<>();
        final AtomicInteger attempts = new AtomicInteger();
        final AtomicInteger transientFailures = new AtomicInteger();
        volatile boolean permanentFailure;

        FakeApi() {
            super(properties(), RestClient.builder());
        }

        @Override
        public void reportResult(JudgeResultDto result) {
            attempts.incrementAndGet();
            if (permanentFailure) {
                throw new JudgeApiException("400 BAD_REQUEST", false);
            }
            if (transientFailures.getAndDecrement() > 0) {
                throw new JudgeApiException("503 SERVICE_UNAVAILABLE", true);
            }
            sent.add(result);
        }
    }

    private static JudgeResultDto result(long submissionId, Verdict verdict) {
        return new JudgeResultDto(submissionId, 1, verdict,
                verdict.isAccepted() ? 100 : 0, 100,
                verdict.isAccepted() ? null : 1, 3, 23, 8192, null, null,
                "may-test", new BigDecimal("1.000"), Instant.now(), List.of());
    }

    private static WorkerProperties properties() {
        return dev.oj.worker.WorkerFixtures.properties(
                java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "oj-worker-test"),
                Duration.ofMillis(5), Duration.ofMillis(20));
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        for (int i = 0; i < 200 && !condition.getAsBoolean(); i++) {
            Thread.sleep(10);
        }
        assertThat(condition.getAsBoolean())
                .as("điều kiện không xảy ra trong 2 giây").isTrue();
    }
}
