package dev.oj.worker.sandbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

    @Test
    @DisplayName("mẫu Java của seed: {dir} và {mem} (KB -> MB)")
    void mauJava() {
        assertThat(CommandTemplate.expand(
                "java -Xmx{mem}m -Xss64m -XX:+UseSerialGC -cp {dir} Main",
                "Main.java", 262_144, PATH))
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
