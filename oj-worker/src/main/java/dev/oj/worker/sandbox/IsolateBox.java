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

/**
 * Một sandbox {@code isolate} đang sống: {@code init} → {@code run}* → {@code cleanup}.
 * Bước 2.3 và 2.6 của {@code build-order.md}.
 *
 * <p>★ Đây là <b>class duy nhất trong cả dự án được phép spawn tiến trình</b> — luật ArchUnit
 * 6 ({@code cau-truc-source.md} mục 6) ép điều đó bằng máy, cho mọi file hiện có và mọi file
 * sau này.
 *
 * <h2>{@link AutoCloseable} chứ không phải một cặp {@code start/stop}</h2>
 * "Dọn box trong {@code finally}" là một câu trong tài liệu mà người ta quên. Cấu trúc
 * {@code try (var box = IsolateBox.open(...))} thì trình biên dịch nhớ hộ. Box rò rỉ là mất
 * một slot vĩnh viễn, và mất đủ slot là hệ thống <b>ngừng chấm mà không báo lỗi gì</b>.
 *
 * <h2>{@link #reset()} giữa mỗi lượt, và lý do rất cụ thể</h2>
 * {@code cg-mem} trong file meta là đỉnh bộ nhớ của cgroup <b>tính từ lúc box sinh ra</b>, chứ
 * không phải của lượt chạy vừa rồi. Đo được: biên dịch xong rồi chạy trong cùng box thì lượt
 * chạy báo 40MB cho một chương trình dùng 1,6MB — đó là đỉnh của {@code g++}. Đặt ngưỡng MLE
 * trên con số ấy là <b>mọi bài đều MLE</b>. {@code cleanup} + {@code init} lại mất ~5ms và
 * trả lại một con số có nghĩa.
 *
 * <h2>Input đi vào bằng fd, không bằng file trong box</h2>
 * {@link #execute} nhận {@code stdinFile} là một đường dẫn <b>trên host</b> và đưa nó cho
 * {@code isolate} qua {@code ProcessBuilder.redirectInput}; tiến trình con thừa hưởng fd đó.
 * Testdata vì thế không bao giờ xuất hiện trong thư mục box (bất biến #1) — test tấn công 10
 * liệt kê {@code /box} và chỉ thấy đúng binary của chính nó.
 */
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

    /** Khởi tạo box {@code boxId}. {@code workDir} nằm NGOÀI box — meta do isolate ghi ra. */
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

    public int boxId() {
        return boxId;
    }

    /**
     * File {@code meta} mà box này ghi ra, <b>ngoài box</b>.
     *
     * <p>Bên gọi phải dựng dòng lệnh bằng đúng đường dẫn này ({@code isolate -M}). Nếu hai
     * bên dùng hai đường dẫn khác nhau thì {@code isolate} ghi vào một chỗ còn worker đọc ở
     * chỗ khác, và <b>mọi lượt chấm đều thành IE</b> — với thông báo "isolate không ghi ra
     * file meta", nghe như sandbox hỏng chứ không như một lỗi nối dây.
     */
    public Path metaFile() {
        return metaFile;
    }

    /** Thư mục {@code /box} nhìn từ phía host. */
    public Path boxDir() {
        if (boxDir == null) {
            throw new SandboxException("box " + boxId + " chưa init");
        }
        return boxDir;
    }

    /** {@code cleanup} rồi {@code init} lại — box rỗng và bộ đếm cgroup về 0. */
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

    /**
     * Đặt một file vào box và bật cờ thực thi.
     *
     * <p>Cần thiết vì binary được lấy ra khỏi box dưới dạng <b>byte</b> (để vào cache biên
     * dịch) rồi đặt lại vào box mới — và {@code Files.write} tạo file 0644. Thiếu dòng
     * {@code chmod} này thì mọi bài C++ đều {@code RE} với {@code exitsig:13}, một triệu chứng
     * không gợi ra nguyên nhân chút nào.
     */
    public void writeExecutable(String name, byte[] content) {
        write(name, content);
        try {
            Files.setPosixFilePermissions(boxDir().resolve(name),
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (IOException e) {
            throw new SandboxException("Không đặt được cờ thực thi cho " + name, e);
        }
    }

    /**
     * Lấy một file ra khỏi box — <b>chỉ khi nó là file thường</b>.
     *
     * <h2>Vì sao có {@code NOFOLLOW_LINKS} ở đây</h2>
     * Đo được: một chương trình 3 dòng xoá binary {@code prog} rồi tạo symlink cùng tên trỏ
     * tới {@code /etc/shadow}. Nếu bước copy artifact đi theo link thì máy chấm <b>tự tay</b>
     * bê {@code /etc/shadow} vào cache binary — và từ đó nó là "chương trình" của một bài nộp.
     *
     * <p>{@code isolate} tự xoá file không-thường ở cuối {@code --run} (vì {@code
     * --special-files} tắt, xem {@link IsolateCommand}), nên trên thực tế link đã biến mất
     * trước khi tới đây. Lớp kiểm này vẫn ở lại: một cờ bị bật nhầm ba năm nữa không được
     * phép biến thành một lỗ hổng đọc file tuỳ ý.
     */
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

    /**
     * Chạy một dòng lệnh đã dựng sẵn trong box này.
     *
     * @param argv        dựng bởi {@link IsolateCommand}; được soi lại lần cuối trước khi spawn
     * @param stdinFile   file <b>trên host</b> làm stdin, hoặc {@code null} nếu không có input
     * @param stdoutLimit trần byte giữ lại của stdout
     * @param stderrLimit trần byte giữ lại của stderr
     * @param watchdog    chốt chặn khi chính {@code isolate} treo — giới hạn thật là của
     *                    {@code isolate}, cái này chỉ để worker không mất một slot vĩnh viễn
     */
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
        // KHÔNG environment().putAll(...): box thừa hưởng môi trường của worker là thừa hưởng
        // cả OJ_INTERNAL_SHARED_SECRET. isolate tự dựng môi trường tối thiểu.
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
                // Tiến trình chết giữa chừng là chuyện bình thường ở đây.
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

    /**
     * ⚠️ Không nuốt lỗi ở đây. Box không dọn được là <b>rò rỉ tài nguyên</b>, không phải
     * chuyện nhỏ: slot đó coi như mất và không có gì báo ({@code oj-worker/CLAUDE.md} mục 6).
     */
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

    /** {@code --init} / {@code --cleanup}: nhanh, không có input, không cần giới hạn output. */
    private String runControl(List<String> argv, String what) {
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

    /** Một lượt chạy: kết luận của sandbox + hai luồng output đã bị cắt theo trần. */
    public record Execution(IsolateMeta meta, OutputLimiter.Captured stdout,
                            OutputLimiter.Captured stderr) {
    }
}
