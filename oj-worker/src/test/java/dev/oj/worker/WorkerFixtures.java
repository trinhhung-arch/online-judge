package dev.oj.worker;

import dev.oj.worker.config.WorkerProperties;
import dev.oj.worker.config.WorkerProperties.Sandbox;
import org.springframework.util.unit.DataSize;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public final class WorkerFixtures {

    private WorkerFixtures() {
    }

    public static Sandbox sandbox(Path cacheDir) {
        return sandbox(cacheDir, 900);
    }

    public static Sandbox sandbox(Path cacheDir, int firstBoxId) {
        return new Sandbox(
                true,
                Path.of("/usr/local/bin/isolate"),
                Path.of("/var/local/lib/isolate"),
                firstBoxId,
                Duration.ofSeconds(10),
                List.of("/usr/bin", "/bin"),
                new Sandbox.Compile(64, 256, DataSize.ofMegabytes(64), DataSize.ofKilobytes(32),
                        Path.of("/opt/oj/pch")),
                new Sandbox.Run(Duration.ofMillis(500), 1, 64,
                        DataSize.ofMegabytes(1), DataSize.ofKilobytes(8),
                        List.of("/proc", "/tmp")),
                new Sandbox.Cache(cacheDir, 512),
                new Sandbox.Benchmark(Duration.ofMinutes(15), 3, 0, 8.0));
    }

    public static WorkerProperties properties(Path cacheDir) {
        return properties(cacheDir, Duration.ofMillis(5), Duration.ofMillis(50));
    }

    public static WorkerProperties properties(Path cacheDir, Duration retryMin, Duration retryMax) {
        return new WorkerProperties("may-test", "arm64", 6,
                Duration.ofSeconds(120), Duration.ofSeconds(30), Duration.ofMillis(10),
                Duration.ofMillis(200),
                Duration.ofSeconds(5), retryMin, retryMax,
                "http://localhost:8080", "x".repeat(32), new BigDecimal("1.000"),
                sandbox(cacheDir));
    }

    public static void requireIsolate(java.nio.file.Path isolateBinary) {
        if (java.nio.file.Files.isExecutable(isolateBinary)) {
            return;
        }
        boolean linux = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                .contains("linux");
        if (!linux) {
            org.junit.jupiter.api.Assumptions.abort("isolate chỉ chạy trên Linux; máy này là "
                    + System.getProperty("os.name") + ". Sandbox được kiểm trong CI Linux.");
        }
        throw new AssertionError(
                "Không tìm thấy " + isolateBinary + " trên một máy Linux. Bộ 14 test tấn công "
                        + "KHÔNG được phép skip — chạy scripts/build-isolate.sh trước. "
                        + "Xem docs/build-order.md Bước 2.1.");
    }
}
