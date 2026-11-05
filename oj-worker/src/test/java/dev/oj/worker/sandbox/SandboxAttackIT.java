package dev.oj.worker.sandbox;

import dev.oj.worker.WorkerFixtures;
import dev.oj.worker.sandbox.SandboxHarness.Attempt;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("14 test tấn công sandbox")
class SandboxAttackIT {

    private static final int CPU_MS = 1_000;
    private static final int MEMORY_KB = 65_536;

    @TempDir
    static Path work;
    private static SandboxHarness harness;

    @BeforeAll
    static void openBox() {
        WorkerFixtures.requireIsolate(WorkerFixtures.sandbox(work).isolateBinary());
        harness = SandboxHarness.open(work);
    }

    @AfterAll
    static void closeBox() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    @DisplayName("1 · fork bomb bị cgroup chặn, host không sinh thêm tiến trình nào")
    void forkBomb() {
        long processesBefore = hostProcessCount();
        Attempt attempt = harness.attack("01-fork-bomb.cpp", CPU_MS, MEMORY_KB);

        assertThat(attempt.meta().outcome())
                .as("fork bomb phải bị dừng, không được chạy tới cùng")
                .isNotEqualTo(IsolateMeta.Outcome.OK);
        assertThat(hostProcessCount() - processesBefore)
                .as("fork bomb thoát ra ngoài cgroup sẽ để lại hàng nghìn tiến trình trên host")
                .isLessThan(200);
    }

    @Test
    @DisplayName("2 · while(1) bị giới hạn CPU giết, và thời gian báo về là CPU time")
    void cpuSpin() {
        Attempt attempt = harness.attack("02-cpu-spin.cpp", CPU_MS, MEMORY_KB);

        assertThat(attempt.meta().outcome()).isEqualTo(IsolateMeta.Outcome.TIME_LIMIT);
        assertThat(attempt.meta().killed()).isTrue();
        assertThat(attempt.meta().cpuTimeMs())
                .as("bị giết ngay sau hạn mức CPU, không phải sau wall")
                .isBetween((long) CPU_MS, CPU_MS + 900L);
    }

    @Test
    @DisplayName("3 · malloc 10GB bị cgroup OOM-kill → MLE, không phải RE")
    void mallocTenGigabytes() {
        Attempt attempt = harness.attack("03-malloc-10gb.cpp", 5_000, MEMORY_KB);

        assertThat(attempt.meta().cgOomKilled()).isTrue();
        assertThat(attempt.meta().outcome())
                .as("SG + cg-oom-killed là MLE. Báo RE thì thí sinh đi tìm một lỗi con trỏ "
                        + "không tồn tại (U2, nfrplan 6.2)")
                .isEqualTo(IsolateMeta.Outcome.MEMORY_LIMIT);
        assertThat(attempt.meta().memoryKb()).isLessThanOrEqualTo(MEMORY_KB);
    }

    @Test
    @DisplayName("12 · in 10GB ra stdout: bị cắt, đĩa host không tăng, worker không phình")
    void stdoutFlood() throws IOException {
        long freeBefore = Files.getFileStore(harness.boxDir()).getUsableSpace();
        Attempt attempt = harness.attack("12-stdout-flood.cpp", CPU_MS, MEMORY_KB);

        assertThat(attempt.meta().outcome())
                .as("chương trình in vô hạn phải chạm giới hạn CPU của chính nó")
                .isEqualTo(IsolateMeta.Outcome.TIME_LIMIT);
        assertThat(attempt.stdout().length())
                .as("OutputLimiter giữ tối đa trần đã đặt, phần thừa đọc rồi vứt")
                .isLessThanOrEqualTo(1 << 20);
        assertThat(Files.getFileStore(harness.boxDir()).getUsableSpace())
                .as("không một byte nào của 10GB được ghi xuống đĩa host")
                .isGreaterThan(freeBefore - (64L << 20));
    }

    @Test
    @DisplayName("13 · tạo 10.000 file trong /box: box vẫn dọn sạch được")
    void manyFiles() {
        Attempt attempt = harness.attack("13-many-files.cpp", 5_000, MEMORY_KB);
        assertThat(attempt.compiled()).isTrue();

        long startedAt = System.nanoTime();
        harness.attack("04-read-etc-passwd.cpp", CPU_MS, MEMORY_KB);
        long cleanupMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(harness.boxEntries())
                .as("box phải sạch trơn trước lượt sau — file rơi rớt là rò rỉ giữa hai bài nộp")
                .doesNotContain("f00000.dat");
        assertThat(cleanupMs)
                .as("dọn box chậm tới mức mất slot cũng là một dạng từ chối dịch vụ")
                .isLessThan(30_000);
    }

    @Test
    @DisplayName("14 · compiler bomb bị giết TRONG box, host không mất một MB RAM nào")
    void compilerBomb() {
        Attempt attempt = harness.attack("14-compiler-bomb.cpp", CPU_MS, MEMORY_KB);

        assertThat(attempt.compiled())
                .as("bom template không được phép biên dịch thành công")
                .isFalse();
        assertThat(attempt.meta().outcome())
                .as("phải bị chính giới hạn của box dừng — hết giờ hoặc hết RAM. Nếu ca này "
                        + "xanh vì g++ chạy xong thì bom quá yếu, không phải sandbox quá mạnh")
                .isIn(IsolateMeta.Outcome.TIME_LIMIT, IsolateMeta.Outcome.MEMORY_LIMIT,
                        IsolateMeta.Outcome.RUNTIME_ERROR);
        assertThat(attempt.compileLog())
                .as("thí sinh phải hiểu vì sao bài của họ không biên dịch được")
                .isNotNull();
    }

    @Test
    @DisplayName("4 · /etc/passwd và /etc/shadow không tồn tại trong box")
    void readEtcPasswd() {
        Attempt attempt = harness.attack("04-read-etc-passwd.cpp", CPU_MS, MEMORY_KB);

        assertThat(attempt.reachedEnd()).isTrue();
        assertThat(attempt.leaked())
                .as("stdout: %s", attempt.stdout())
                .isFalse();
    }

    @Test
    @DisplayName("5 · không có mạng: connect và DNS đều hỏng (không bao giờ --share-net)")
    void outboundSocket() {
        Attempt attempt = harness.attack("05-socket-outbound.cpp", CPU_MS, MEMORY_KB);

        assertThat(attempt.reachedEnd()).isTrue();
        assertThat(attempt.leaked())
                .as("có mạng trong box thì bài nộp tải được lời giải, và moi được API nội bộ. "
                        + "stdout: %s", attempt.stdout())
                .isFalse();
    }

    @Test
    @DisplayName("6 · không ghi được ra ngoài /box, kể cả /tmp và đường leo cấp")
    void writeOutsideBox() {
        Attempt attempt = harness.attack("06-write-outside-box.cpp", CPU_MS, MEMORY_KB);

        assertThat(attempt.reachedEnd()).isTrue();
        assertThat(attempt.leaked())
                .as("/tmp có trong mount mặc định của isolate và ghi được — nó bị gỡ ở "
                        + "oj.worker.sandbox.run.hidden-dirs. stdout: %s", attempt.stdout())
                .isFalse();
    }

    @Test
    @DisplayName("7 · exec /bin/sh được, nhưng shell cũng bị nhốt y hệt")
    void execShell() {
        Attempt attempt = harness.attack("07-exec-shell.cpp", CPU_MS, MEMORY_KB);

        assertThat(attempt.leaked())
                .as("điều phải đúng không phải là 'không exec được shell' mà là 'shell không "
                        + "làm được gì'. stdout: %s", attempt.stdout())
                .isFalse();
    }

    @Test
    @DisplayName("8 · ptrace vào tiến trình khác đều hỏng")
    void ptrace() {
        Attempt attempt = harness.attack("08-ptrace.cpp", CPU_MS, MEMORY_KB);

        assertThat(attempt.reachedEnd()).isTrue();
        assertThat(attempt.leaked())
                .as("attach được vào một tiến trình khác là đọc được bộ nhớ của nó. "
                        + "stdout: %s", attempt.stdout())
                .isFalse();
    }

    @Test
    @DisplayName("9 · symlink không thoát được, và cái bẫy đặt cho host cũng không ăn")
    void symlinkEscape() {
        Attempt attempt = harness.attack("09-symlink-escape.cpp", CPU_MS, MEMORY_KB);

        assertThat(attempt.reachedEnd()).isTrue();
        assertThat(attempt.leaked())
                .as("stdout: %s", attempt.stdout())
                .isFalse();
        assertThat(attempt.stdout())
                .as("ca này chỉ có giá trị nếu cái bẫy thật sự được đặt")
                .contains("TRAP:prog->/etc/shadow");
        assertThat(harness.boxEntries())
                .as("isolate xoá file không-thường ở cuối --run (--special-files TẮT), nên "
                        + "symlink giả làm artifact đã biến mất trước khi host chạm tới")
                .doesNotContain("prog");
    }

    @Test
    @DisplayName("★ 10 · testdata KHÔNG nằm trong box — input chỉ đi qua stdin")
    void readTestdata() {
        Attempt attempt = harness.attack("10-read-testdata.cpp", CPU_MS, MEMORY_KB);

        assertThat(attempt.reachedEnd()).isTrue();
        assertThat(attempt.leaked())
                .as("stdout: %s", attempt.stdout())
                .isFalse();
        List<String> seen = attempt.stdout().lines()
                .filter(line -> line.startsWith("BOXENTRY:"))
                .map(line -> line.substring("BOXENTRY:".length()))
                .toList();
        assertThat(seen)
                .as("thấy bất cứ thứ gì khác trong /box nghĩa là có file lọt vào cùng bài nộp; "
                        + "nếu đó là testdata thì nộp sai từng test một là rút được cả bộ đề "
                        + "(SEC3, frplan 3.1)")
                .containsExactly("prog");
        assertThat(harness.boxEntries()).containsExactly("prog");
    }

    @Test
    @DisplayName("11 · /proc bị gỡ: không moi được OJ_INTERNAL_SHARED_SECRET")
    void procSelfEnviron() {
        Attempt attempt = harness.attack("11-proc-self-environ.cpp", CPU_MS, MEMORY_KB);

        assertThat(attempt.reachedEnd()).isTrue();
        assertThat(attempt.leaked())
                .as("/proc CÓ trong mount mặc định của isolate và /proc/self/environ đọc "
                        + "được — nó bị gỡ ở hidden-dirs. stdout: %s", attempt.stdout())
                .isFalse();
        assertThat(attempt.stdout())
                .as("dù có đọc được gì thì cũng không được có bóng dáng secret")
                .doesNotContain("OJ_INTERNAL", "SHARED_SECRET");
    }

    private static long hostProcessCount() {
        return ProcessHandle.allProcesses().count();
    }
}
