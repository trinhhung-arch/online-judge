package dev.oj.worker.sandbox;

import dev.oj.worker.config.WorkerProperties.Sandbox;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Dựng tham số cho {@code isolate}. Bước 2.3 của {@code build-order.md}.
 *
 * <h2>Vì sao đây là một class riêng, thuần, không chạy gì cả</h2>
 * Vì cách duy nhất để kiểm "{@code --share-net} không bao giờ xuất hiện" bằng máy là biến câu
 * đó thành một assertion trên một {@code List<String>}. {@link IsolateBox} thì phải có
 * {@code isolate} thật mới chạy được; class này chạy trong unit test, trên mọi máy, mỗi lần
 * push — kể cả máy của người không cài được sandbox.
 *
 * <h2>Bốn cờ không bao giờ được xuất hiện, và chuyện gì xảy ra nếu có</h2>
 * <table><caption>Cấm</caption>
 *   <tr><td>{@code --share-net}</td><td>box dùng chung network namespace với worker → chương
 *       trình người dùng gọi thẳng được {@code /internal/judge/result} bằng secret nó moi
 *       được, hoặc đơn giản là tải bài giải từ Internet</td></tr>
 *   <tr><td>{@code --special-files}</td><td>isolate giữ lại file không-thường do box tạo ra.
 *       Đo được: một chương trình thay binary {@code prog} bằng symlink tới
 *       {@code /etc/shadow}; với cờ này thì bước copy artifact ra khỏi box tự tay bê
 *       {@code /etc/shadow} vào cache (test tấn công 9)</td></tr>
 *   <tr><td>{@code --inherit-fds}</td><td>box thừa hưởng mọi fd đang mở của worker — trong đó
 *       có kết nối tới API</td></tr>
 *   <tr><td>{@code -e} / {@code --full-env}</td><td>box thừa hưởng môi trường của worker, mà
 *       trong đó có {@code OJ_INTERNAL_SHARED_SECRET} (test tấn công 11)</td></tr>
 * </table>
 *
 * <p>{@link #assertNoForbiddenFlags(List)} chạy trên <b>mọi</b> dòng lệnh trước khi
 * {@link IsolateBox} spawn nó, nên bốn điều trên đúng cả với dòng lệnh do người sau này thêm.
 */
public final class IsolateCommand {

    /** Thư mục làm việc bên trong box. Tên này do isolate quy định, không đổi được. */
    public static final String BOX_DIR = "/box";

    /**
     * Nơi precompiled header xuất hiện <b>bên trong</b> box (Bước 3.5).
     *
     * <p>Read-only, và chỉ ở bước biên dịch. Bước chạy không thấy nó — mã của người dùng
     * không có lý do nào để đọc một PCH, và mỗi thư mục nhìn thấy được là một bề mặt tấn công.
     */
    public static final String PCH_DIR = "/pch";

    private static final List<String> FORBIDDEN =
            List.of("--share-net", "--special-files", "--inherit-fds", "--full-env", "-e");

    private IsolateCommand() {
    }

    /** {@code isolate --cg -b N --init} — in ra đường dẫn box trên stdout. */
    public static List<String> init(Sandbox cfg, int boxId) {
        return check(List.of(cfg.isolateBinary().toString(), "--cg", "-b", str(boxId), "--init"));
    }

    /** {@code isolate --cg -b N --cleanup} — xoá sạch, kể cả file do box tạo ra. */
    public static List<String> cleanup(Sandbox cfg, int boxId) {
        return check(List.of(cfg.isolateBinary().toString(), "--cg", "-b", str(boxId), "--cleanup"));
    }

    /**
     * Bước biên dịch — <b>cũng trong box</b> (bất biến #4).
     *
     * <p>Compiler bomb là có thật và đo được: một file 20 dòng template làm {@code cc1plus}
     * chạm trần 512MB sau 4,4 giây rồi bị cgroup giết. Không có sandbox ở bước này thì con số
     * đó là RAM của host, và nó xảy ra <b>trước khi</b> có bất kỳ dòng mã người dùng nào chạy.
     *
     * @param timeLimitMs {@code languages.compile_time_limit_ms}, từ hợp đồng
     * @param memoryKb    {@code languages.compile_memory_kb}, từ hợp đồng
     */
    public static List<String> compile(Sandbox cfg, int boxId, Path metaFile,
                                       int timeLimitMs, int memoryKb, List<String> program) {
        List<String> argv = base(cfg, boxId, metaFile);
        limits(argv, timeLimitMs, 2L * timeLimitMs, memoryKb,
                cfg.compile().processes(), cfg.compile().openFiles(),
                cfg.compile().maxFileSize().toKilobytes());
        // g++ tự tìm cc1plus/as/collect2 trong PATH; HOME để nó không dò /root.
        argv.add("-E");
        argv.add("PATH=/usr/bin:/bin");
        argv.add("-E");
        argv.add("HOME=" + BOX_DIR);
        argv.add("-E");
        argv.add("LANG=C.UTF-8");
        // ★ Bước 3.5 — PCH gắn READ-ONLY, và chỉ ở đây. `maybe` để một host chưa chạy
        // scripts/build-pch.sh vẫn biên dịch được: isolate bỏ qua quy tắc nếu thư mục không
        // tồn tại, GCC bỏ qua -I trỏ vào hư không, và bài vẫn chấm đúng — chỉ chậm hơn.
        if (cfg.compile().pchDir() != null) {
            argv.add("--dir=" + PCH_DIR + "=" + cfg.compile().pchDir() + ":maybe");
        }
        // Đo được: lệnh biên dịch trong seed (-pipe) chạy sạch khi gỡ cả /proc lẫn /tmp.
        hide(argv, cfg.run().hiddenDirs());
        return finish(cfg, argv, program);
    }

    /**
     * Bước chạy.
     *
     * <p>Môi trường chỉ có {@code PATH}, và chỉ vì {@code languages.run_command} của ngôn ngữ
     * thông dịch viết {@code python3 {src}} chứ không viết đường dẫn tuyệt đối. Một hằng số
     * không mang bí mật nào. Mọi biến khác bị bỏ — đặc biệt là môi trường của worker, nơi có
     * {@code OJ_INTERNAL_SHARED_SECRET} (test tấn công 11).
     *
     * @param cpuLimitMs  đã nhân {@code host_factor} bởi bên gọi. Class này không biết
     *                    {@code host_factor} tồn tại, và không được biết — nhân hai lần là
     *                    lỗi im lặng nhất trong cả hệ thống
     * @param wallLimitMs {@code 2 ×} CPU, chặn chương trình ngủ hoặc chờ I/O vô hạn
     */
    public static List<String> run(Sandbox cfg, int boxId, Path metaFile,
                                   long cpuLimitMs, long wallLimitMs, int memoryKb,
                                   List<String> program) {
        List<String> argv = base(cfg, boxId, metaFile);
        limits(argv, cpuLimitMs, wallLimitMs, memoryKb,
                cfg.run().processes(), cfg.run().openFiles(),
                cfg.run().maxFileSize().toKilobytes());
        // Ân hạn: chương trình vượt giờ không bị giết ngay, nhờ đó thời gian THẬT được báo
        // cáo thay vì đúng bằng giới hạn — "vượt 1ms" và "lặp vô hạn" là hai chuyện khác nhau.
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
        argv.add("--cg");            // cgroup v2: CPU, RAM, số tiến trình đều do kernel ép
        argv.add("-b");
        argv.add(str(boxId));
        argv.add("-M");
        argv.add(metaFile.toString());
        argv.add("-s");              // im lặng: dòng "OK (0.1 sec)" của isolate không phải
        return argv;                 // kết quả, và trộn nó vào stderr của bài làm nhiễu chẩn đoán
    }

    private static void limits(List<String> argv, long cpuMs, long wallMs, int memoryKb,
                               int processes, int openFiles, long fileSizeKb) {
        argv.add("-t");
        argv.add(seconds(cpuMs));          // CPU time, KHÔNG phải wall — máy tải nặng thì cùng
        argv.add("-w");                    // một bài lúc AC lúc TLE, và đó là mất công bằng
        argv.add(seconds(wallMs));
        argv.add("--cg-mem");              // trần của CẢ cgroup, không phải address space của
        argv.add(str(memoryKb));           // một tiến trình: fork ra 50 con cũng không lách được
        argv.add("--processes=" + processes);
        argv.add("-n");
        argv.add(str(openFiles));
        argv.add("-f");
        argv.add(str(fileSizeKb));
    }

    /** {@code --dir=X=} xoá một quy tắc mount mặc định của isolate. */
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

    /**
     * Chốt cuối cùng trước khi spawn. Cố ý kiểm trên dòng lệnh đã dựng xong chứ không kiểm
     * trong từng phương thức: một dòng lệnh do người sau này thêm cũng đi qua đây.
     */
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

    /** isolate nhận giây có phần thập phân. {@code Locale.ROOT} vì {@code vi-VN} dùng dấu phẩy. */
    private static String seconds(long millis) {
        return String.format(Locale.ROOT, "%.3f", millis / 1000.0);
    }

    private static String str(long value) {
        return Long.toString(value);
    }
}
