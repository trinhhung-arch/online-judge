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

final class SandboxHarness implements AutoCloseable {

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

    record Attempt(IsolateMeta meta, String stdout, String stderr, boolean compiled,
                   String compileLog) {

        boolean leaked() {
            return stdout.contains("LEAK:");
        }

        boolean reachedEnd() {
            return stdout.contains("DONE");
        }
    }

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
