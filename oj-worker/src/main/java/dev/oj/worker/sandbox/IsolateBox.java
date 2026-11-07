package dev.oj.worker.sandbox;

import dev.oj.worker.config.WorkerProperties.Sandbox;
import dev.oj.worker.run.OutputLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class IsolateBox implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(IsolateBox.class);

    private final Sandbox cfg;
    private final int boxId;
    private final Path metaFile;
    private Path boxDir;

    private IsolateBox(Sandbox cfg, int boxId, Path metaFile) {
        this.cfg = cfg;
        this.boxId = boxId;
        this.metaFile = metaFile;
    }

    public static IsolateBox open(Sandbox cfg, int boxId, Path workDir) {
        try {
            Files.createDirectories(workDir);
        } catch (IOException e) {
            throw new SandboxException("Không tạo được thư mục làm việc " + workDir, e);
        }
        IsolateBox box = new IsolateBox(cfg, boxId, workDir.resolve("box-" + boxId + ".meta"));
        box.init();
        return box;
    }

    public static void donDep(Sandbox cfg, int boxId) {
        try {
            chayLenhDieuKhien(IsolateCommand.cleanup(cfg, boxId), "cleanup", boxId);
            log.debug("Đã dọn box {}", boxId);
        } catch (RuntimeException e) {
            log.error("KHÔNG DỌN ĐƯỢC box {} lúc tắt máy — cần xoá tay {}",
                    boxId, cfg.boxRoot().resolve(String.valueOf(boxId)), e);
        }
    }

    public int boxId() {
        return boxId;
    }

    public Path metaFile() {
        return metaFile;
    }

    public Path boxDir() {
        if (boxDir == null) {
            throw new SandboxException("box " + boxId + " chưa init");
        }
        return boxDir;
    }

    public void reset() {
        cleanupQuietly();
        init();
    }

    public void write(String name, byte[] content) {
        try {
            Files.write(boxDir().resolve(name), content);
        } catch (IOException e) {
            throw new SandboxException("Không ghi được " + name + " vào box " + boxId, e);
        }
    }

    public void write(String name, String content) {
        write(name, content.getBytes(StandardCharsets.UTF_8));
    }

    public void writeExecutable(String name, byte[] content) {
        write(name, content);
        try {
            Files.setPosixFilePermissions(boxDir().resolve(name),
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (IOException e) {
            throw new SandboxException("Không đặt được cờ thực thi cho " + name, e);
        }
    }

    public byte[] takeRegularFile(String name) {
        Path file = boxDir().resolve(name);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new SandboxException(
                    "Artifact '" + name + "' của box " + boxId + " không phải file thường. "
                            + "Hoặc bước biên dịch không tạo ra nó, hoặc chương trình đã thay "
                            + "nó bằng symlink để lừa máy chấm copy một file ngoài box");
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new SandboxException("Không đọc được artifact " + name, e);
        }
    }

    public boolean hasRegularFile(String name) {
        return Files.isRegularFile(boxDir().resolve(name), LinkOption.NOFOLLOW_LINKS);
    }

    public Execution execute(List<String> argv, Path stdinFile,
                             long stdoutLimit, long stderrLimit, Duration watchdog) {
        IsolateCommand.assertNoForbiddenFlags(argv);
        try {
            Files.deleteIfExists(metaFile);
        } catch (IOException e) {
            throw new SandboxException("Không xoá được meta cũ " + metaFile, e);
        }

        ProcessBuilder builder = new ProcessBuilder(argv);
        builder.redirectInput(stdinFile != null
                ? ProcessBuilder.Redirect.from(stdinFile.toFile())
                : ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));
        builder.environment().clear();

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new SandboxException("Không chạy được " + cfg.isolateBinary()
                    + ". Chưa build sandbox? Xem scripts/build-isolate.sh", e);
        }

        AtomicReference<OutputLimiter.Captured> stderr =
                new AtomicReference<>(OutputLimiter.Captured.empty());
        Thread errPump = new Thread(() -> {
            try (InputStream in = process.getErrorStream()) {
                stderr.set(OutputLimiter.drain(in, stderrLimit));
            } catch (IOException ignored) {
            }
        }, "oj-stderr-box" + boxId);
        errPump.setDaemon(true);
        errPump.start();

        OutputLimiter.Captured stdout;
        try (InputStream in = process.getInputStream()) {
            stdout = OutputLimiter.drain(in, stdoutLimit);
            if (!process.waitFor(watchdog.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new SandboxException("isolate box " + boxId + " không thoát sau "
                        + watchdog + " — chính sandbox treo, không phải bài nộp");
            }
            errPump.join(1_000);
        } catch (IOException e) {
            throw new SandboxException("Lỗi đọc output của box " + boxId, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new SandboxException("Bị ngắt khi đang chấm trong box " + boxId, e);
        }

        return new Execution(readMeta(), stdout, stderr.get());
    }

    private IsolateMeta readMeta() {
        try {
            return IsolateMeta.parse(Files.exists(metaFile)
                    ? Files.readAllLines(metaFile, StandardCharsets.UTF_8)
                    : List.of("status:XX", "message:isolate không ghi ra file meta"));
        } catch (IOException e) {
            throw new SandboxException("Không đọc được meta " + metaFile, e);
        }
    }

    private void init() {
        String output = runControl(IsolateCommand.init(cfg, boxId), "init");
        boxDir = Path.of(output.trim()).resolve("box");
        if (!Files.isDirectory(boxDir)) {
            throw new SandboxException("isolate --init trả về '" + output.trim()
                    + "' nhưng không có thư mục box ở đó");
        }
    }

    @Override
    public void close() {
        try {
            runControl(IsolateCommand.cleanup(cfg, boxId), "cleanup");
        } catch (RuntimeException e) {
            log.error("KHÔNG DỌN ĐƯỢC box {} — slot này coi như mất cho tới khi có người vào "
                    + "xoá tay {}. Đây là rò rỉ tài nguyên, cần alert.",
                    boxId, cfg.boxRoot().resolve(String.valueOf(boxId)), e);
        }
        boxDir = null;
    }

    private void cleanupQuietly() {
        try {
            runControl(IsolateCommand.cleanup(cfg, boxId), "cleanup");
        } catch (RuntimeException e) {
            log.warn("cleanup box {} không thành công, init lại đè lên: {}", boxId, e.toString());
        }
    }

    private String runControl(List<String> argv, String what) {
        return chayLenhDieuKhien(argv, what, boxId);
    }

    private static String chayLenhDieuKhien(List<String> argv, String what, int boxId) {
        try {
            Process process = new ProcessBuilder(argv).redirectErrorStream(true).start();
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new SandboxException("isolate --" + what + " treo quá 30 giây");
            }
            if (process.exitValue() != 0) {
                throw new SandboxException("isolate --" + what + " box " + boxId + " thất bại "
                        + "(mã " + process.exitValue() + "): " + output.strip());
            }
            return output;
        } catch (IOException e) {
            throw new SandboxException("Không chạy được isolate --" + what, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SandboxException("Bị ngắt khi isolate --" + what, e);
        }
    }

    public record Execution(IsolateMeta meta, OutputLimiter.Captured stdout,
                            OutputLimiter.Captured stderr) {
    }
}
