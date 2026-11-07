package dev.oj.worker.sandbox;

import dev.oj.worker.config.WorkerProperties.Sandbox;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class IsolateCommand {

    public static final String BOX_DIR = "/box";

    public static final String PCH_DIR = "/pch";

    private static final List<String> FORBIDDEN =
            List.of("--share-net", "--special-files", "--inherit-fds", "--full-env", "-e");

    private IsolateCommand() {
    }

    public static List<String> init(Sandbox cfg, int boxId) {
        return check(List.of(cfg.isolateBinary().toString(), "--cg", "-b", str(boxId), "--init"));
    }

    public static List<String> cleanup(Sandbox cfg, int boxId) {
        return check(List.of(cfg.isolateBinary().toString(), "--cg", "-b", str(boxId), "--cleanup"));
    }

    public static List<String> compile(Sandbox cfg, int boxId, Path metaFile,
                                       int timeLimitMs, int memoryKb, List<String> program) {
        List<String> argv = base(cfg, boxId, metaFile);
        limits(argv, timeLimitMs, 2L * timeLimitMs, memoryKb,
                cfg.compile().processes(), cfg.compile().openFiles(),
                cfg.compile().maxFileSize().toKilobytes());
        argv.add("-E");
        argv.add("PATH=/usr/bin:/bin");
        argv.add("-E");
        argv.add("HOME=" + BOX_DIR);
        argv.add("-E");
        argv.add("LANG=C.UTF-8");
        if (cfg.compile().pchDir() != null) {
            argv.add("--dir=" + PCH_DIR + "=" + cfg.compile().pchDir() + ":maybe");
        }
        hide(argv, cfg.run().hiddenDirs());
        return finish(cfg, argv, program);
    }

    public static List<String> run(Sandbox cfg, int boxId, Path metaFile,
                                   long cpuLimitMs, long wallLimitMs, int memoryKb,
                                   List<String> program) {
        List<String> argv = base(cfg, boxId, metaFile);
        limits(argv, cpuLimitMs, wallLimitMs, memoryKb,
                cfg.run().processes(), cfg.run().openFiles(),
                cfg.run().maxFileSize().toKilobytes());
        argv.add("-x");
        argv.add(seconds(cfg.run().extraTime().toMillis()));
        argv.add("-E");
        argv.add("PATH=/usr/bin:/bin");
        hide(argv, cfg.run().hiddenDirs());
        return finish(cfg, argv, program);
    }

    private static List<String> base(Sandbox cfg, int boxId, Path metaFile) {
        List<String> argv = new ArrayList<>(32);
        argv.add(cfg.isolateBinary().toString());
        argv.add("--cg");
        argv.add("-b");
        argv.add(str(boxId));
        argv.add("-M");
        argv.add(metaFile.toString());
        argv.add("-s");
        return argv;
    }

    private static void limits(List<String> argv, long cpuMs, long wallMs, int memoryKb,
                               int processes, int openFiles, long fileSizeKb) {
        argv.add("-t");
        argv.add(seconds(cpuMs));
        argv.add("-w");
        argv.add(seconds(wallMs));
        argv.add("--cg-mem");
        argv.add(str(memoryKb));
        argv.add("--processes=" + processes);
        argv.add("-n");
        argv.add(str(openFiles));
        argv.add("-f");
        argv.add(str(fileSizeKb));
    }

    private static void hide(List<String> argv, List<String> dirs) {
        for (String dir : dirs) {
            argv.add("--dir=" + dir + "=");
        }
    }

    private static List<String> finish(Sandbox cfg, List<String> argv, List<String> program) {
        argv.add("-c");
        argv.add(BOX_DIR);
        argv.add("--run");
        argv.add("--");
        argv.addAll(program);
        return check(argv);
    }

    public static List<String> assertNoForbiddenFlags(List<String> argv) {
        for (String arg : argv) {
            for (String bad : FORBIDDEN) {
                if (arg.equals(bad) || arg.startsWith(bad + "=")) {
                    throw new IllegalStateException(
                            "Cờ bị cấm trong dòng lệnh isolate: " + bad + ". Xem javadoc "
                                    + "IsolateCommand — bốn cờ này mở đúng bốn đường rò rỉ, và "
                                    + "không cờ nào trong số đó có lý do chính đáng ở một OJ");
                }
            }
        }
        return argv;
    }

    private static List<String> check(List<String> argv) {
        return assertNoForbiddenFlags(argv);
    }

    private static String seconds(long millis) {
        return String.format(Locale.ROOT, "%.3f", millis / 1000.0);
    }

    private static String str(long value) {
        return Long.toString(value);
    }
}
