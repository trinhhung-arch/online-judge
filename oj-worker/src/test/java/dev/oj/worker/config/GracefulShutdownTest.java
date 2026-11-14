package dev.oj.worker.config;

import dev.oj.contract.JudgeResultDto;
import dev.oj.contract.Verdict;
import dev.oj.worker.WorkerFixtures;
import dev.oj.worker.client.JudgeApiClient;
import dev.oj.worker.client.ResultBuffer;
import dev.oj.worker.config.WorkerProperties.Sandbox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class GracefulShutdownTest {

    @Test
    @DisplayName("★ SC-03 · tắt êm khi còn kết quả chưa gửi: chờ trong ân hạn, API hồi lại thì gửi nốt rồi mới dừng")
    void cho_gui_not_ket_qua_trong_an_han() throws Exception {
        var api = new ApiGia();
        var props = properties(Duration.ofSeconds(5));
        try (var buffer = new ResultBuffer(api, props)) {
            buffer.submit(ketQua(1L));
            assertThat(buffer.pendingCount()).isEqualTo(1);

            Thread.ofPlatform().start(() -> {
                ngu(200);
                api.song = true;
            });
            long batDau = System.nanoTime();
            new GracefulShutdown(buffer, props).stop();
            Duration mat = Duration.ofNanos(System.nanoTime() - batDau);

            assertThat(api.daNhan).extracting(JudgeResultDto::submissionId).containsExactly(1L);
            assertThat(buffer.pendingCount()).isZero();
            assertThat(mat).as("dừng ngay khi gửi xong, không ngồi hết ân hạn")
                    .isLessThan(Duration.ofSeconds(4));
        }
    }

    @Test
    @DisplayName("★ SC-03 · API không hồi lại: hết ân hạn thì vẫn dừng, kết quả còn lại để reaper giao lại")
    void het_an_han_thi_van_dung() throws Exception {
        var api = new ApiGia();
        var props = properties(Duration.ofMillis(300));
        try (var buffer = new ResultBuffer(api, props)) {
            buffer.submit(ketQua(2L));

            long batDau = System.nanoTime();
            new GracefulShutdown(buffer, props).stop();
            Duration mat = Duration.ofNanos(System.nanoTime() - batDau);

            assertThat(mat).as("chờ đúng ân hạn").isGreaterThanOrEqualTo(Duration.ofMillis(300));
            assertThat(mat).as("không treo máy chờ API mãi").isLessThan(Duration.ofSeconds(3));
            assertThat(api.daNhan).isEmpty();
            assertThat(buffer.pendingCount()).as("không vứt kết quả trong lúc tắt").isEqualTo(1);
        }
    }

    private static WorkerProperties properties(Duration anHan) {
        Path cache = Path.of(System.getProperty("java.io.tmpdir"), "oj-worker-test");
        WorkerProperties goc = WorkerFixtures.properties(cache, Duration.ofMillis(5),
                Duration.ofMillis(20));
        Sandbox s = goc.sandbox();
        Sandbox tat = new Sandbox(false, s.isolateBinary(), s.boxRoot(), s.firstBoxId(),
                s.watchdogSlack(), s.programPath(), s.compile(), s.run(), s.cache(),
                s.benchmark());
        return new WorkerProperties(goc.hostName(), goc.arch(), goc.slots(), goc.lease(),
                goc.progressInterval(), goc.idlePoll(), anHan, goc.requestTimeout(), goc.resultRetryMin(),
                goc.resultRetryMax(), goc.apiBaseUrl(), goc.internalSecret(), goc.hostFactor(),
                tat);
    }

    private static JudgeResultDto ketQua(long submissionId) {
        return new JudgeResultDto(submissionId, 1, Verdict.AC, 100, 100, null, 3, 23, 8192,
                null, null, "may-test", new BigDecimal("1.000"), Instant.now(), List.of());
    }

    private static void ngu(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class ApiGia extends JudgeApiClient {

        final List<JudgeResultDto> daNhan = new CopyOnWriteArrayList<>();
        volatile boolean song;

        ApiGia() {
            super(WorkerFixtures.properties(
                    Path.of(System.getProperty("java.io.tmpdir"), "oj-worker-test")),
                    RestClient.builder());
        }

        @Override
        public void reportResult(JudgeResultDto result) {
            if (!song) {
                throw new JudgeApiException("503 SERVICE_UNAVAILABLE", true);
            }
            daNhan.add(result);
        }
    }
}
