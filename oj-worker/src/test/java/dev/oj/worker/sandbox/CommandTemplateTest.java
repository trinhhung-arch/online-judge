package dev.oj.worker.sandbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Mẫu lệnh ở đây là bản chép nguyên văn từ {@code R__seed_du_lieu_tham_chieu.sql}. */
class CommandTemplateTest {

    private static final List<String> PATH = List.of("/usr/bin", "/bin");

    @Test
    @DisplayName("mẫu C++ của seed: {bin} và {src} thành đường dẫn tuyệt đối trong box")
    void mauCpp() {
        assertThat(CommandTemplate.expand(
                "g++ -std=gnu++20 -O2 -pipe -static -o {bin} {src}", "Main.cpp", 262_144, PATH))
                .containsSubsequence("-o", "/box/prog", "/box/Main.cpp")
                .first().asString().endsWith("/g++").startsWith("/");
    }

    /**
     * ★ Ca này KHÔNG được tra {@code java} trên máy đang chạy test.
     *
     * <p>{@code resolveProgram} tra bằng {@code Files.isExecutable} trên hệ thống tệp thật,
     * mà ảnh chạy CỐ Ý không có {@code java} trong {@code program-path}: JRE của worker nằm
     * ở {@code /opt/java/openjdk} vì Java của worker không phải thứ được phép chạy trong box
     * ({@code infra/isolate/Dockerfile}). macOS thì có {@code /usr/bin/java}. Nên bản cũ
     * xanh trên máy dev và ĐỎ trong chính ảnh máy chấm — đo thật ngày 2026-09-05, nó làm
     * surefire chết trước khi failsafe kịp chạy ca tấn công nào.
     *
     * <p>Thứ ca này đo là phép thay {@code dir} và {@code mem} (KB -&gt; MB), không phải máy
     * nào có sẵn {@code java}. Nên dựng một {@code java} giả rồi tra trong thư mục ấy. Mẫu
     * lệnh vẫn chép nguyên văn từ {@code R__seed_du_lieu_tham_chieu.sql}.
     *
     * <p>Việc ảnh chạy không có {@code java} vẫn là thật và vẫn đúng: dòng {@code java21}
     * trong seed đang {@code enabled = FALSE}. Ai bật lại nó phải thêm JDK vào ảnh trước.
     */
    @Test
    @DisplayName("mẫu Java của seed: {dir} và {mem} (KB -> MB)")
    void mauJava(@TempDir Path thuMucBin) throws IOException {
        Path java = Files.createFile(thuMucBin.resolve("java"));
        assertThat(java.toFile().setExecutable(true))
                .as("không đặt được cờ thực thi cho %s", java)
                .isTrue();

        assertThat(CommandTemplate.expand(
                "java -Xmx{mem}m -Xss64m -XX:+UseSerialGC -cp {dir} Main",
                "Main.java", 262_144, List.of(thuMucBin.toString())))
                .contains("-Xmx256m", "-cp", "/box", "Main");
    }

    @Test
    @DisplayName("★ argv[0] tương đối được tra thành tuyệt đối — isolate không tra PATH")
    void traArgv0() {
        assertThat(CommandTemplate.expand("g++ -o {bin} {src}", "Main.cpp", 65_536, PATH).get(0))
                .as("execve(\"g++\") cho exitcode 127, và triệu chứng là MỌI bài nộp đều RE")
                .isEqualTo("/usr/bin/g++");
    }

    @Test
    @DisplayName("argv[0] đã tuyệt đối thì giữ nguyên, kể cả khi không có trên máy build")
    void giuNguyenDuongDanTuyetDoi() {
        assertThat(CommandTemplate.expand("/opt/gcc-15/bin/g++ {src}", "Main.cpp", 65_536, PATH)
                .get(0)).isEqualTo("/opt/gcc-15/bin/g++");
    }

    @Test
    @DisplayName("không tìm thấy chương trình thì báo rõ, không để tới lúc chấm mới hỏng")
    void baoLoiKhiKhongTim() {
        assertThatThrownBy(() -> CommandTemplate.expand(
                "khong-ton-tai-dau {src}", "Main.cpp", 65_536, PATH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("execve");
    }

    @Test
    @DisplayName("ký tự shell bị từ chối — lệnh chạy thẳng bằng execve, không qua sh")
    void tuChoiKyTuShell() {
        assertThatThrownBy(() -> CommandTemplate.expand(
                "g++ {src} && rm -rf /", "Main.cpp", 65_536, PATH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("execve");
    }
}
