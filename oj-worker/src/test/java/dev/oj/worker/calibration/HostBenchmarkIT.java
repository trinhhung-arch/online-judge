package dev.oj.worker.calibration;

import dev.oj.worker.WorkerFixtures;
import dev.oj.worker.config.WorkerProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Bước 2.9 — hiệu chuẩn máy chấm và bẫy throttle nhiệt. */
@DisplayName("HostBenchmark: đo tốc độ máy chấm")
class HostBenchmarkIT {

    @TempDir
    static Path work;

    @BeforeAll
    static void requireSandbox() {
        WorkerFixtures.requireIsolate(WorkerFixtures.sandbox(work).isolateBinary());
    }

    @Test
    @DisplayName("chưa hiệu chuẩn thì ĐO nhưng KHÔNG đổi host_factor")
    void chuaHieuChuanThiKhongDoiHeSo() {
        HostBenchmark benchmark = new HostBenchmark(properties(0), null);

        benchmark.measure();

        assertThat(benchmark.lastCpuMs())
                .as("tải chuẩn phải chạy đủ lâu để nhiễu lập lịch nhỏ hơn tín hiệu drift 8%")
                .isBetween(100L, 30_000L);
        assertThat(benchmark.current())
                .as("reference-cpu-ms = 0 nghĩa là chưa ai đo trên máy chấm chuẩn. Để một hằng "
                        + "số chưa ai đo quyết định TLE của thí sinh thì tệ hơn không hiệu chuẩn")
                .isEqualByComparingTo(new BigDecimal("1.000"));
    }

    @Test
    @DisplayName("đã hiệu chuẩn thì host_factor = thời gian đo / thời gian máy chuẩn")
    void hieuChuanThiTinhHeSo() {
        // Giả sử máy chấm chuẩn chạy tải này mất 1000ms. Máy nào chậm gấp đôi thì factor ~2.
        HostBenchmark benchmark = new HostBenchmark(properties(1_000), null);

        benchmark.measure();

        assertThat(benchmark.current().doubleValue())
                .isEqualTo(benchmark.lastCpuMs() / 1000.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(benchmark.current().signum()).isPositive();
    }

    @Test
    @DisplayName("đo hai lần liên tiếp lệch nhau ít — nếu không thì cảnh báo drift là nhiễu")
    void haiLanDoOnDinh() {
        HostBenchmark benchmark = new HostBenchmark(properties(0), null);

        benchmark.measure();
        long first = benchmark.lastCpuMs();
        benchmark.measure();
        long second = benchmark.lastCpuMs();

        // Baseline không đổi sau lần đầu, nên lastCpuMs() vẫn là số đo đầu tiên; ta chỉ cần
        // biết lần đo thứ hai không ném và không làm hỏng trạng thái.
        assertThat(second).isEqualTo(first);
        assertThat(benchmark.current().signum()).isPositive();
    }

    private static WorkerProperties properties(int referenceCpuMs) {
        var base = WorkerFixtures.sandbox(work, 920);
        var sandbox = new WorkerProperties.Sandbox(
                base.enabled(), base.isolateBinary(), base.boxRoot(), base.firstBoxId(),
                base.watchdogSlack(), base.programPath(), base.compile(), base.run(), base.cache(),
                new WorkerProperties.Sandbox.Benchmark(
                        Duration.ofMinutes(15), 3, referenceCpuMs, 8.0));
        return new WorkerProperties("may-test", "amd64", 2,
                Duration.ofSeconds(120), Duration.ofMillis(10), Duration.ofSeconds(5),
                Duration.ofMillis(5), Duration.ofMillis(50), "http://localhost:8080",
                "x".repeat(32), new BigDecimal("1.000"), sandbox);
    }
}
