package dev.oj.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "oj.worker")
public record WorkerProperties(
        String hostName,
        String arch,
        int slots,
        Duration lease,
        Duration progressInterval,
        Duration idlePoll,
        Duration shutdownGrace,
        Duration requestTimeout,
        Duration resultRetryMin,
        Duration resultRetryMax,
        String apiBaseUrl,
        String internalSecret,
        java.math.BigDecimal hostFactor,
        Sandbox sandbox) {

    public WorkerProperties {
        if (hostName == null || hostName.isBlank()) {
            throw new IllegalStateException("oj.worker.host-name bắt buộc — API dùng nó để tra "
                    + "judge_hosts và để ghi vào judge_runs");
        }
        if (slots < 1 || slots > 32) {
            throw new IllegalStateException(
                    "oj.worker.slots ngoài [1..32]: " + slots + ". Khớp CHECK trên judge_hosts, "
                            + "và đọc ADR 008 trước khi tăng con số này");
        }
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
            throw new IllegalStateException("oj.worker.api-base-url bắt buộc");
        }
        if (internalSecret == null || internalSecret.length() < 32) {
            throw new IllegalStateException(
                    "Thiếu OJ_INTERNAL_SHARED_SECRET (cần >= 32 ký tự). Đây là thứ duy nhất "
                            + "cho worker quyền ghi verdict — không có giá trị mặc định, cố ý");
        }
        if (shutdownGrace == null || shutdownGrace.isNegative()) {
            throw new IllegalStateException(
                    "oj.worker.shutdown-grace không hợp lệ: " + shutdownGrace
                            + ". Đây là thời gian tối đa chờ slot chấm nốt bài đang chạy khi "
                            + "nhận SIGTERM (Bước 6.8). Đặt 0 là quay lại hành vi cắt ngang "
                            + "của M1, tức là mỗi lần deploy đội thêm 120 giây chờ reaper cho "
                            + "mỗi bài đang chấm dở");
        }
        if (idlePoll == null || idlePoll.isZero() || idlePoll.isNegative()) {
            throw new IllegalStateException("oj.worker.idle-poll phải dương");
        }
        if (progressInterval == null || progressInterval.isZero() || progressInterval.isNegative()
                || lease == null || progressInterval.compareTo(lease) >= 0) {
            throw new IllegalStateException(
                    "oj.worker.progress-interval phải dương và ngắn hơn oj.worker.lease: "
                            + progressInterval + " so với " + lease + ". Mỗi lô tiến độ gia hạn "
                            + "lease phía API (ADR 026) — gửi thưa hơn lease là bài đang chấm "
                            + "bị reaper thu hồi");
        }
        if (sandbox == null) {
            throw new IllegalStateException("oj.worker.sandbox bắt buộc — không có mặc định "
                    + "ngầm cho thứ quyết định mã người lạ chạy trong cái gì");
        }
    }

    public record Sandbox(
            boolean enabled,
            Path isolateBinary,
            Path boxRoot,
            int firstBoxId,
            Duration watchdogSlack,
            List<String> programPath,
            Compile compile,
            Run run,
            Cache cache,
            Benchmark benchmark) {

        public Sandbox {
            if (isolateBinary == null || boxRoot == null) {
                throw new IllegalStateException(
                        "oj.worker.sandbox.isolate-binary và .box-root bắt buộc");
            }
            if (firstBoxId < 0 || firstBoxId > 999) {
                throw new IllegalStateException("first-box-id ngoài [0..999]: " + firstBoxId);
            }
            programPath = programPath == null || programPath.isEmpty()
                    ? List.of("/usr/bin", "/bin")
                    : List.copyOf(programPath);
            if (compile == null || run == null || cache == null || benchmark == null) {
                throw new IllegalStateException("thiếu nhóm compile/run/cache/benchmark");
            }
            if (enabled && cache.dir() != null) {
                kiemCacheNgoaiBox(cache.dir(), boxRoot, compile.pchDir());
            }
        }

        private static final List<String> THU_MUC_ISOLATE_GAN_VAO_BOX =
                List.of("/bin", "/dev", "/lib", "/lib64", "/usr", "/proc");

        private static void kiemCacheNgoaiBox(Path cacheDir, Path boxRoot, Path pchDir) {
            Path cache = cacheDir.toAbsolutePath().normalize();
            List<Path> cam = new java.util.ArrayList<>();
            THU_MUC_ISOLATE_GAN_VAO_BOX.forEach(d -> cam.add(Path.of(d)));
            cam.add(boxRoot);
            if (pchDir != null) {
                cam.add(pchDir);
            }
            for (Path goc : cam) {
                if (cache.startsWith(goc.toAbsolutePath().normalize())) {
                    throw new IllegalStateException("oj.worker.sandbox.cache.dir = " + cacheDir
                            + " nằm dưới " + goc + ", một thư mục isolate gắn vào box. Cache "
                            + "chứa input và đáp án của mọi đề — đặt nó ở đó là mọi bài nộp "
                            + "đọc được đáp án. Dùng một thư mục như /var/tmp/oj-worker");
                }
            }
        }

        public record Compile(int processes, int openFiles, DataSize maxFileSize,
                              DataSize logLimit, Path pchDir) {
        }

        public record Run(Duration extraTime, int processes, int openFiles, DataSize maxFileSize,
                          DataSize stderrLimit, List<String> hiddenDirs) {

            public Run {
                hiddenDirs = hiddenDirs == null ? List.of() : List.copyOf(hiddenDirs);
                if (processes < 1) {
                    throw new IllegalStateException("run.processes phải >= 1");
                }
            }
        }

        public record Cache(Path dir, int maxEntries) {
        }

        public record Benchmark(Duration interval, int samples, int referenceCpuMs,
                                double driftAlertPct) {

            public boolean calibrated() {
                return referenceCpuMs > 0;
            }
        }
    }
}
