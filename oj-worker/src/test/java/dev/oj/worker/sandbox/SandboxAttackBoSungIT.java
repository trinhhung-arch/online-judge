package dev.oj.worker.sandbox;

import dev.oj.worker.WorkerFixtures;
import dev.oj.worker.sandbox.SandboxHarness.Attempt;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Tấn công bổ sung: lúc biên dịch, và bằng Python")
class SandboxAttackBoSungIT {

    private static final int CPU_MS = 2_000;
    private static final int MEMORY_KB = 65_536;
    private static final String BI_MAT = "DAP_AN_AN_9f3e1c";

    @TempDir
    static Path work;
    private static SandboxHarness harness;
    private static Path tepBiMat;

    @BeforeAll
    static void openBox() throws IOException {
        WorkerFixtures.requireIsolate(WorkerFixtures.sandbox(work).isolateBinary());
        harness = SandboxHarness.open(work);
        tepBiMat = Files.writeString(harness.config().cache().dir().resolve("bi-mat.in"),
                BI_MAT + "\n");
    }

    @AfterAll
    static void closeBox() {
        if (harness != null) {
            harness.close();
        }
    }

    @Nested
    @DisplayName("★ BM-4 · #include đọc tệp của máy chủ lúc biên dịch")
    class LucBienDich {

        @Test
        @DisplayName("#include tệp testdata trong cache của worker (đường dẫn tuyệt đối, biết trước) → không biên dịch được, log không có nội dung")
        void include_tep_trong_cache_worker() {
            Attempt attempt = bienDich("#include \"" + tepBiMat.toAbsolutePath() + "\"\n");

            assertThat(attempt.compiled()).isFalse();
            assertThat(attempt.compileLog())
                    .as("compiler chép dòng của tệp được include vào thông báo lỗi — đó là "
                            + "đường rút testdata qua log compiler, thứ mà thí sinh luôn thấy")
                    .doesNotContain(BI_MAT)
                    .contains("No such file");
        }

        @Test
        @DisplayName("#include /etc/passwd, /etc/shadow và đường vòng ../../ → không có trong box")
        void include_tep_he_thong() {
            for (String duongDan : List.of("/etc/passwd", "/etc/shadow",
                    "../../../../../../etc/passwd")) {
                Attempt attempt = bienDich("#include \"" + duongDan + "\"\n");

                assertThat(attempt.compiled()).as(duongDan).isFalse();
                assertThat(attempt.compileLog()).as(duongDan)
                        .doesNotContain("root:", "daemon:", "nobody:");
            }
        }

        @Test
        @DisplayName("#include /proc/self/environ → /proc bị gỡ cả ở bước biên dịch, không lộ biến môi trường")
        void include_proc_self_environ() {
            Attempt attempt = bienDich("#include \"/proc/self/environ\"\n");

            assertThat(attempt.compiled()).isFalse();
            assertThat(attempt.compileLog())
                    .doesNotContain("PATH=", "HOME=", "OJ_", "SECRET");
        }

        private Attempt bienDich(String dauTep) {
            return harness.attackSource(dauTep + "int main() { return 0; }\n", CPU_MS, MEMORY_KB);
        }
    }

    @Nested
    @DisplayName("★ BM-5 · cùng các đòn của 14 ca C++, viết bằng Python")
    class BangPython {

        @Test
        @DisplayName("fork bomb: không fork được tiến trình nào, host không sinh thêm tiến trình")
        void fork_bomb() {
            long truoc = SandboxAttackIT.hostProcessCount();
            Attempt attempt = harness.attackPython("01-fork-bomb.py", CPU_MS, MEMORY_KB);

            assertThat(attempt.stdout()).as("stderr: %s", attempt.stderr())
                    .contains("FORKED:0", "DONE");
            assertThat(SandboxAttackIT.hostProcessCount() - truoc).isLessThan(200);
        }

        @Test
        @DisplayName("đọc /etc, /proc, /tmp, cache testdata và biến môi trường: không thấy gì; /box chỉ có bài nộp")
        void doc_tep_va_bien_moi_truong() {
            Attempt attempt = harness.attackPython("02-doc-tep.py", CPU_MS, MEMORY_KB);

            assertThat(attempt.reachedEnd()).as("stderr: %s", attempt.stderr()).isTrue();
            assertThat(attempt.leaked()).as("stdout: %s", attempt.stdout()).isFalse();
            assertThat(attempt.stdout().lines().filter(l -> l.startsWith("BOXENTRY:")))
                    .containsExactly("BOXENTRY:Main.py");
        }

        @Test
        @DisplayName("mạng: không kết nối được ra ngoài, tới API, Postgres hay Redis trên máy; DNS hỏng")
        void mang() {
            Attempt attempt = harness.attackPython("03-mang.py", CPU_MS, MEMORY_KB);

            assertThat(attempt.reachedEnd()).as("stderr: %s", attempt.stderr()).isTrue();
            assertThat(attempt.leaked()).as("stdout: %s", attempt.stdout()).isFalse();
        }

        @Test
        @DisplayName("ghi ra ngoài /box: /usr, /tmp, /etc, /var, /home, / đều không ghi được")
        void ghi_ngoai_box() {
            Attempt attempt = harness.attackPython("04-ghi-ngoai-box.py", CPU_MS, MEMORY_KB);

            assertThat(attempt.reachedEnd()).as("stderr: %s", attempt.stderr()).isTrue();
            assertThat(attempt.leaked()).as("stdout: %s", attempt.stdout()).isFalse();
        }

        @Test
        @DisplayName("os.execv sang /bin/sh: exec được, nhưng shell không làm được gì")
        void exec_shell() {
            Attempt attempt = harness.attackPython("05-exec-shell.py", CPU_MS, MEMORY_KB);

            assertThat(attempt.leaked()).as("stdout: %s", attempt.stdout()).isFalse();
        }
    }
}
