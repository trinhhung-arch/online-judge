package dev.oj.worker.sandbox;

import java.util.ArrayList;
import java.util.List;

/**
 * Đổi mẫu lệnh của bảng {@code languages} thành {@code argv} chạy được trong box.
 *
 * <pre>
 *   g++ -std=gnu++20 -O2 -pipe -static -o {bin} {src}
 *   java -Xmx{mem}m -Xss64m -XX:+UseSerialGC -cp {dir} Main
 * </pre>
 *
 * <h2>Bốn chỗ thay, và tất cả đều là đường dẫn TUYỆT ĐỐI bên trong box</h2>
 * {@code /box/prog} chứ không phải {@code ./prog}: lệnh không phụ thuộc thư mục hiện tại, và
 * {@code argv[0]} không phải tra {@code PATH} — mà {@code PATH} thì bước chạy cố tình không có.
 *
 * <h2>Không có shell, và đó là một quyết định chứ không phải thiếu sót</h2>
 * {@code argv} đi thẳng vào {@code execve}. Không {@code sh -c}, không nối chuỗi, nên không có
 * chỗ nào để một dấu {@code ;} trong dữ liệu trở thành một lệnh. Đổi lại, ký tự đặc biệt của
 * shell trong mẫu lệnh sẽ <b>không</b> hoạt động — nên {@link #split} từ chối chúng ngay thay
 * vì để người viết config tin rằng chúng có tác dụng rồi ngồi gỡ lỗi một lệnh im lặng làm sai.
 */
public final class CommandTemplate {

    /** Tên artifact sau khi biên dịch, bên trong box. */
    public static final String BINARY_NAME = "prog";

    private static final String SHELL_METACHARACTERS = "|&;<>()$`\\\"'*?~";

    private CommandTemplate() {
    }

    /**
     * @param template       {@code languages.compile_command} hoặc {@code run_command}
     * @param sourceFileName tên file mã nguồn trong box, ví dụ {@code Main.cpp}
     * @param memoryLimitKb  cho {@code {mem}}, đổi sang MB vì {@code -Xmx} nhận MB
     *                       <p>{@code {pch}} trỏ tới thư mục precompiled header trong box
     *                       (Bước 3.5). Seed viết {@code -I{pch}}; nếu host chưa dựng PCH thì
     *                       thư mục không tồn tại và GCC bỏ qua — chậm, không sai.
     */
    public static List<String> expand(String template, String sourceFileName, int memoryLimitKb,
                                      List<String> programPath) {
        List<String> argv = new ArrayList<>();
        for (String word : split(template)) {
            argv.add(word
                    .replace("{bin}", IsolateCommand.BOX_DIR + '/' + BINARY_NAME)
                    .replace("{src}", IsolateCommand.BOX_DIR + '/' + sourceFileName)
                    .replace("{dir}", IsolateCommand.BOX_DIR)
                    .replace("{pch}", IsolateCommand.PCH_DIR)
                    .replace("{mem}", Integer.toString(Math.max(1, memoryLimitKb / 1024))));
        }
        if (argv.isEmpty()) {
            throw new IllegalArgumentException("Mẫu lệnh rỗng");
        }
        argv.set(0, resolveProgram(argv.get(0), programPath));
        return List.copyOf(argv);
    }

    /**
     * ★ {@code isolate} gọi {@code execve}, <b>không tra {@code PATH}</b>.
     *
     * <p>Đo được: {@code --run -- g++ ...} cho {@code execve("g++"): No such file or directory}
     * và {@code exitcode:127}. Trên đường chấm thật thì triệu chứng là <b>mọi bài nộp đều
     * RE</b>, với thông báo duy nhất "Exited with error status 127" — không có gì trong đó
     * gợi ra rằng nguyên nhân là một đường dẫn thiếu.
     *
     * <p>Tra ở phía host là đúng chứ không phải tạm bợ: {@code /usr} và {@code /bin} được
     * isolate mount vào box ở nguyên đường dẫn cũ, nên cái tìm thấy ngoài này cũng là cái
     * chạy được trong kia. Và nó cho phép bảng {@code languages} viết {@code g++} thay vì
     * {@code /usr/bin/g++} — quan trọng vì ảnh ARM để compiler ở chỗ khác ảnh x86 (C1).
     */
    private static String resolveProgram(String program, List<String> searchPath) {
        if (program.startsWith("/")) {
            return program;
        }
        for (String dir : searchPath) {
            java.nio.file.Path candidate = java.nio.file.Path.of(dir, program);
            if (java.nio.file.Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        throw new IllegalArgumentException(
                "Không tìm thấy '" + program + "' trong " + searchPath + ". isolate dùng execve "
                        + "chứ không tra PATH, nên argv[0] phải là đường dẫn tuyệt đối — hoặc "
                        + "thêm thư mục vào oj.worker.sandbox.program-path");
    }

    private static List<String> split(String template) {
        for (int i = 0; i < template.length(); i++) {
            if (SHELL_METACHARACTERS.indexOf(template.charAt(i)) >= 0) {
                throw new IllegalArgumentException(
                        "Mẫu lệnh chứa ký tự shell '" + template.charAt(i) + "': " + template
                                + ". Lệnh chạy thẳng bằng execve, không qua shell — ký tự này "
                                + "sẽ được truyền nguyên văn làm tham số chứ không có tác dụng "
                                + "gì, và đó là loại sai lầm im lặng nhất trong cấu hình");
            }
        }
        return List.of(template.strip().split("\\s+"));
    }
}
