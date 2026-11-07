package dev.oj.worker.sandbox;

import java.util.ArrayList;
import java.util.List;

public final class CommandTemplate {

    public static final String BINARY_NAME = "prog";

    private static final String SHELL_METACHARACTERS = "|&;<>()$`\\\"'*?~";

    private CommandTemplate() {
    }

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
