package dev.oj.worker.sandbox;

import dev.oj.worker.WorkerFixtures;
import dev.oj.worker.config.WorkerProperties.Sandbox;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Chạy một file trong {@code oj-worker/src/test/resources/attacks/} qua <b>đúng hai bộ dựng
 * lệnh của production</b> — {@link IsolateCommand#compile} và {@link IsolateCommand#run}.
 *
 * <p>Đây là điểm mấu chốt của cả bộ test: nếu harness tự dựng lấy dòng lệnh isolate của
 * riêng nó thì 14 ca xanh chứng minh rằng <i>harness</i> an toàn, chứ không chứng minh gì về
 * hệ thống thật. Mọi cờ ở đây đến từ cùng một chỗ mà một bài nộp thật đi qua.
 */
final class SandboxHarness implements AutoCloseable {

    /**
     * Dải box id của test — cách xa {@code first-box-id} của worker thật (0..5) để chạy test
     * trên máy đang có worker chạy không phá lượt chấm nào.
     */
    static final int BOX_ID = 900;

    private final Sandbox cfg;
    private final Path work;
    private final IsolateBox box;

    private SandboxHarness(Path work) {
        this.work = work;
        this.cfg = WorkerFixtures.sandbox(work);
        this.box = IsolateBox.open(cfg, BOX_ID, work.resolve("meta"));
    }

    static SandboxHarness open(Path work) {
        return new SandboxHarness(work);
    }

    /** Kết quả một lượt tấn công. {@code stdout} là thứ ta soi để tìm dấu hiệu rò rỉ. */
    record Attempt(IsolateMeta meta, String stdout, String stderr, boolean compiled,
                   String compileLog) {

        /** Mọi file tấn công in {@code LEAK:...} khi nó lấy được thứ lẽ ra không lấy được. */
        boolean leaked() {
            return stdout.contains("LEAK:");
        }

        boolean reachedEnd() {
            return stdout.contains("DONE");
        }
    }

    /**
     * Biên dịch rồi chạy một file tấn công.
     *
     * @param cpuLimitMs giới hạn CPU của lượt chạy — cố tình ngắn, mọi ca đều phải kết thúc
     *                   trong vài giây kể cả ca cố tình chạy vô hạn
     */
    Attempt attack(String attackFile, int cpuLimitMs, int memoryKb) {
        String source = readResource("/attacks/" + attackFile);

        box.reset();
        box.write("Main.cpp", source);
        IsolateBox.Execution compile = box.execute(
                IsolateCommand.compile(cfg, BOX_ID, box.metaFile(), 10_000, 524_288,
                        CommandTemplate.expand(
                                "g++ -std=gnu++20 -O2 -pipe -static -o {bin} {src}",
                                "Main.cpp", memoryKb, cfg.programPath())),
                null, 65_536, 65_536, Duration.ofSeconds(40));

        String compileLog = compile.stderr().text() + compile.stdout().text();
        if (compile.meta().outcome() != IsolateMeta.Outcome.OK
                || !box.hasRegularFile(CommandTemplate.BINARY_NAME)) {
            return new Attempt(compile.meta(), "", "", false, compileLog);
        }

        byte[] binary = box.takeRegularFile(CommandTemplate.BINARY_NAME);
        box.reset();
        box.writeExecutable(CommandTemplate.BINARY_NAME, binary);

        IsolateBox.Execution run = box.execute(
                IsolateCommand.run(cfg, BOX_ID, box.metaFile(),
                        cpuLimitMs, 2L * cpuLimitMs, memoryKb,
                        List.of(IsolateCommand.BOX_DIR + '/' + CommandTemplate.BINARY_NAME)),
                stdinFile(), 1 << 20, 1 << 16, Duration.ofSeconds(30));

        return new Attempt(run.meta(), run.stdout().text(), run.stderr().text(), true, compileLog);
    }

    /**
     * File input nằm <b>ngoài</b> box và đi vào chương trình qua một fd đã mở — đúng cách
     * {@code TestRunner} làm. Nếu nó nằm trong box thì ca tấn công 10 mất hết ý nghĩa.
     */
    private Path stdinFile() {
        try {
            Path file = work.resolve("stdin.txt");
            if (!Files.exists(file)) {
                Files.writeString(file, "DAP-AN-BI-MAT-KHONG-DUOC-PHEP-NAM-TRONG-BOX\n");
            }
            return file;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Nội dung thư mục box nhìn từ host — dùng để khẳng định testdata không lọt vào. */
    List<String> boxEntries() {
        try (var stream = Files.list(box.boxDir())) {
            return stream.map(p -> p.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    Path boxDir() {
        return box.boxDir();
    }

    Sandbox config() {
        return cfg;
    }

    @Override
    public void close() {
        box.close();
    }

    private static String readResource(String path) {
        try (InputStream in = SandboxHarness.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new AssertionError("Thiếu file tấn công " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
