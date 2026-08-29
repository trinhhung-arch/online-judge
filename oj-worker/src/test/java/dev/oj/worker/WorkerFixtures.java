package dev.oj.worker;

import dev.oj.worker.config.WorkerProperties;
import dev.oj.worker.config.WorkerProperties.Sandbox;
import org.springframework.util.unit.DataSize;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Cấu hình worker cho test. Một chỗ duy nhất, để thêm một thuộc tính vào
 * {@link WorkerProperties} là sửa một file chứ không phải mười file.
 *
 * <p>Các con số ở đây <b>khớp {@code application.yml}</b> có chủ ý: một bộ test chạy với giới
 * hạn khác giới hạn thật thì nó kiểm một hệ thống khác với hệ thống được deploy.
 */
public final class WorkerFixtures {

    private WorkerFixtures() {
    }

    public static Sandbox sandbox(Path cacheDir) {
        return sandbox(cacheDir, 900);
    }

    /**
     * @param firstBoxId hai lớp IT chạy nối tiếp nhau vẫn nên dùng hai dải box khác nhau: một
     *                   box chưa kịp {@code --cleanup} vì test trước hỏng sẽ làm test sau đỏ
     *                   với lý do hoàn toàn không liên quan
     */
    public static Sandbox sandbox(Path cacheDir, int firstBoxId) {
        return new Sandbox(
                true,
                Path.of("/usr/local/bin/isolate"),
                Path.of("/var/local/lib/isolate"),
                // Dải box riêng cho test: không giẫm lên worker thật đang chạy trên cùng máy.
                firstBoxId,
                Duration.ofSeconds(10),
                List.of("/usr/bin", "/bin"),
                new Sandbox.Compile(64, 256, DataSize.ofMegabytes(64), DataSize.ofKilobytes(32)),
                new Sandbox.Run(Duration.ofMillis(500), 1, 64,
                        DataSize.ofMegabytes(1), DataSize.ofKilobytes(8),
                        List.of("/proc", "/tmp")),
                new Sandbox.Cache(cacheDir, 512),
                new Sandbox.Benchmark(Duration.ofMinutes(15), 3, 0, 8.0));
    }

    public static WorkerProperties properties(Path cacheDir) {
        return properties(cacheDir, Duration.ofMillis(5), Duration.ofMillis(50));
    }

    /** Nhịp retry là thứ duy nhất test khác nhau cần chỉnh — nó quyết định test chạy bao lâu. */
    public static WorkerProperties properties(Path cacheDir, Duration retryMin, Duration retryMax) {
        return new WorkerProperties("may-test", "arm64", 6,
                Duration.ofSeconds(120), Duration.ofMillis(10), Duration.ofSeconds(5),
                retryMin, retryMax,
                "http://localhost:8080", "x".repeat(32), new BigDecimal("1.000"),
                sandbox(cacheDir));
    }

    /**
     * ⛔ Cổng vào của mọi test cần sandbox thật.
     *
     * <p>Trên Linux mà không có {@code isolate} thì <b>fail</b>, không skip. Bộ 14 ca tấn
     * công là điều kiện để {@code IsolateJudgeRunner} được đăng ký thay
     * {@code ScriptedJudgeRunner}; một cái skip lặng lẽ trong CI biến cái cổng đó thành một
     * lời hứa, và {@code nfrplan} 4.5 nói rõ "fail 1 case = fail build".
     *
     * <p>Trên macOS thì bỏ qua, vì {@code isolate} là phần mềm chỉ có trên Linux — máy của
     * Người A không thể chạy nó, và bắt họ fail mỗi lần {@code mvnw verify} không làm hệ
     * thống an toàn hơn một chút nào. Host thật chạy Linux trong VM, CI chạy Linux.
     */
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
