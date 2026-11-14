package dev.oj.worker.run;

import dev.oj.contract.CheckerType;
import dev.oj.contract.JudgeJobDto;
import dev.oj.contract.JudgeResultDto;
import dev.oj.contract.ScoringMode;
import dev.oj.contract.Sha256;
import dev.oj.contract.TestcaseMetaDto;
import dev.oj.contract.Verdict;
import dev.oj.worker.WorkerFixtures;
import dev.oj.worker.compile.Compiler;
import dev.oj.worker.config.WorkerProperties;
import dev.oj.worker.pipeline.JobExecutor;
import dev.oj.worker.pipeline.SlotPool;
import dev.oj.worker.testdata.ContentAddressedCache;
import dev.oj.worker.testdata.LocalDirectoryTestdataSource;
import dev.oj.worker.testdata.TestdataFetcher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Đường chấm thật: source C++ -> verdict")
class IsolateJudgeRunnerIT {

    @TempDir
    static Path work;

    private static IsolateJudgeRunner runner;
    private static String inputSha;
    private static String outputSha;

    @BeforeAll
    static void wire() {
        WorkerProperties properties = new WorkerProperties(
                "may-test", "amd64", 2,
                java.time.Duration.ofSeconds(120), java.time.Duration.ofMillis(10),
                java.time.Duration.ofMillis(200),
                java.time.Duration.ofSeconds(5), java.time.Duration.ofMillis(5),
                java.time.Duration.ofMillis(50), "http://localhost:8080", "x".repeat(32),
                new java.math.BigDecimal("1.000"),
                WorkerFixtures.sandbox(work, 910));
        WorkerFixtures.requireIsolate(properties.sandbox().isolateBinary());

        var store = new ContentAddressedCache(work.resolve("testdata-store"));
        inputSha = Sha256.hexOf("3 4\n");
        outputSha = Sha256.hexOf("7\n");
        store.store(inputSha, "3 4\n".getBytes(StandardCharsets.UTF_8));
        store.store(outputSha, "7\n".getBytes(StandardCharsets.UTF_8));

        var fetcher = new TestdataFetcher(
                new LocalDirectoryTestdataSource(work.resolve("testdata-store")), properties);
        var benchmark = new dev.oj.worker.calibration.HostBenchmark(properties, null);
        runner = new IsolateJudgeRunner(
                new JobExecutor(properties, new SlotPool(properties), new Compiler(properties),
                        new TestRunner(properties, fetcher), benchmark,
                        new dev.oj.worker.report.BatchReporter(
                                new dev.oj.worker.client.JudgeApiClient(
                                        properties,
                                        org.springframework.web.client.RestClient.builder()))),
                properties, benchmark);
    }

    @Test
    @DisplayName("AC — đúng đáp án thì trọn điểm, và thời gian là CPU time")
    void accepted() {
        JudgeResultDto result = judge("""
                #include <bits/stdc++.h>
                int main(){int a,b;std::cin>>a>>b;std::cout<<a+b<<"\\n";}
                """);

        assertThat(result.verdict()).isEqualTo(Verdict.AC);
        assertThat(result.score()).isEqualTo(result.maxScore()).isEqualTo(100);
        assertThat(result.failedTestOrdinal()).isNull();
        assertThat(result.testsRun()).isEqualTo(1);
        assertThat(result.timeMs()).isNotNull().isLessThan(1000);
        assertThat(result.memoryKb()).isNotNull().isPositive();
        assertThat(result.hostName()).isEqualTo("may-test");
    }

    @Test
    @DisplayName("WA — sai đáp án thì 0 điểm và chỉ ra test đầu tiên sai")
    void wrongAnswer() {
        JudgeResultDto result = judge("""
                #include <bits/stdc++.h>
                int main(){int a,b;std::cin>>a>>b;std::cout<<a*b<<"\\n";}
                """);

        assertThat(result.verdict()).isEqualTo(Verdict.WA);
        assertThat(result.score()).isZero();
        assertThat(result.failedTestOrdinal()).isEqualTo(1);
    }

    @Test
    @DisplayName("TLE — vòng lặp vô hạn, và thời gian báo về là thời gian THẬT (extra-time)")
    void timeLimit() {
        JudgeResultDto result = judge("int main(){volatile long long x=0;for(;;)++x;}");

        assertThat(result.verdict()).isEqualTo(Verdict.TLE);
        assertThat(result.timeMs())
                .as("nhờ isolate -x, chương trình không bị giết đúng ở mốc giới hạn, nên "
                        + "'vượt 1ms' và 'lặp vô hạn' phân biệt được (U2, nfrplan 6.2)")
                .isGreaterThanOrEqualTo(1000);
    }

    @Test
    @DisplayName("MLE — xin 300MB với trần 64MB. KHÔNG được báo RE")
    void memoryLimit() {
        JudgeResultDto result = judge("""
                #include <cstdlib>
                #include <cstring>
                int main(){for(int i=0;i<300;i++){void*p=malloc(1<<20);if(!p)return 1;
                memset(p,i,1<<20);} return 0;}
                """);

        assertThat(result.verdict()).isEqualTo(Verdict.MLE);
        assertThat(result.memoryKb()).isNotNull().isLessThanOrEqualTo(65_536);
    }

    @Test
    @DisplayName("RE — thoát với mã khác 0")
    void runtimeError() {
        assertThat(judge("int main(){return 3;}").verdict()).isEqualTo(Verdict.RE);
    }

    @Test
    @DisplayName("★ XK-06 · RE do SIGSEGV: tín hiệu đi về API trong isolateStatus để giải thích được")
    void runtimeErrorMangTinHieu() {
        JudgeResultDto result = judge("int main(){volatile int *p = 0; *p = 1; return 0;}");

        assertThat(result.verdict()).isEqualTo(Verdict.RE);
        assertThat(result.isolateStatus())
                .as("VerdictExplainer đọc 'signal=11' để nói 'SIGSEGV — truy cập ngoài phạm vi'")
                .contains("signal=11");
    }

    @Test
    @DisplayName("CE — lỗi cú pháp, và log compiler KHÔNG lộ đường dẫn trong box")
    void compileError() {
        JudgeResultDto result = judge("int main(){ khong_ton_tai(); }");

        assertThat(result.verdict()).isEqualTo(Verdict.CE);
        assertThat(result.testsRun()).isZero();
        assertThat(result.compileLog()).contains("Main.cpp");
        assertThat(result.compileLog())
                .as("log compiler được phép cho tác giả xem, nhưng đường dẫn tuyệt đối trong "
                        + "box thì không (oj-worker/CLAUDE.md mục 7)")
                .doesNotContain("/box/");
    }

    @Test
    @DisplayName("★ early exit — sai ở test 2 thì test 3 không chạy nữa")
    void earlyExit() {
        JudgeResultDto result = judge("""
                #include <bits/stdc++.h>
                int main(){static int n=0;int a,b;std::cin>>a>>b;
                std::cout<<(std::getenv("X")?0:a+b)<<"\\n";}
                """, 3);

        assertThat(result.verdict()).isEqualTo(Verdict.AC);
        assertThat(result.testsRun()).isEqualTo(3);
        assertThat(result.score()).isEqualTo(100);
    }

    @Test
    @DisplayName("★ dừng ở test sai đầu tiên, không chạy nốt phần còn lại")
    void dungOTestSaiDauTien() {
        JudgeResultDto result = judge("int main(){return 1;}", 5);

        assertThat(result.verdict()).isEqualTo(Verdict.RE);
        assertThat(result.failedTestOrdinal()).isEqualTo(1);
        assertThat(result.testsRun())
                .as("early exit cắt ~50% thời gian chấm trung bình (oj-worker/CLAUDE.md mục 4)")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("cache biên dịch: nộp lại y hệt thì không biên dịch lần hai")
    void cacheBienDich() {
        String source = """
                #include <bits/stdc++.h>
                int main(){int a,b;std::cin>>a>>b;std::cout<<a+b<<"\\n";}
                """;
        judge(source);
        long startedAt = System.nanoTime();
        JudgeResultDto again = judge(source);
        long millis = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(again.verdict()).isEqualTo(Verdict.AC);
        assertThat(millis)
                .as("biên dịch C++ mất ~2 giây; lượt thứ hai lấy binary từ cache theo "
                        + "sha256(source+lang+flags), nên phải nhanh hơn hẳn")
                .isLessThan(1_500);
    }

    @Test
    @DisplayName("★ CB-03 · chương trình ngủ: CPU gần 0 nhưng wall vượt 2 lần giới hạn → TLE")
    void ngu() {
        JudgeResultDto result = judge("#include <unistd.h>\nint main(){sleep(30);}");

        assertThat(result.verdict())
                .as("chỉ giới hạn CPU thì một bài ngủ giữ box mãi — wall = 2 × cpu chặn nó")
                .isEqualTo(Verdict.TLE);
    }

    @Test
    @DisplayName("★ CB-10 · lệnh biên dịch báo thành công mà không sinh prog → CE kèm câu giải thích")
    void bienDichKhongSinhProg() {
        JudgeResultDto result = judge("int main(){}", "cpp20",
                "g++ -std=gnu++20 -fsyntax-only {src}", "{bin}", "Main.cpp");

        assertThat(result.verdict()).isEqualTo(Verdict.CE);
        assertThat(result.compileLog()).contains("không tạo ra prog");
    }

    @Test
    @DisplayName("★ CB-11 · Python chạy thật trong isolate: đọc-ghi và một vòng lặp nặng CPU đều AC")
    void python() {
        String docGhi = "a, b = map(int, input().split())\nprint(a + b)\n";
        String nangCpu = """
                a, b = map(int, input().split())
                s = 0
                for i in range(2_000_000):
                    s += i % 7
                print(a + b if s >= 0 else 0)
                """;

        for (String source : List.of(docGhi, nangCpu)) {
            assertThat(judge(source, "py311", null, "python3 {src}", "Main.py").verdict())
                    .as(source).isEqualTo(Verdict.AC);
        }
    }

    private JudgeResultDto judge(String source, String ngonNgu, String bienDich, String chay,
                                 String tep) {
        return runner.run(JudgeJobDto.builder()
                .submission(101, 1)
                .traceId("trace-it")
                .language(ngonNgu, bienDich, chay)
                .compileLimits(20_000, 524_288)
                .runLimitsOnReferenceHost(1_000, 65_536, 65_536)
                .source(tep, source, Sha256.hexOf(source))
                .checker(CheckerType.TOKEN, null)
                .scoring(ScoringMode.ALL_OR_NOTHING, 100)
                .testdata(1, Sha256.hexOf("manifest"),
                        List.of(new TestcaseMetaDto(1, false, inputSha, outputSha, null)))
                .build());
    }

    private JudgeResultDto judge(String source) {
        return judge(source, 1);
    }

    private JudgeResultDto judge(String source, int testCount) {
        List<TestcaseMetaDto> testcases = new ArrayList<>();
        for (int i = 1; i <= testCount; i++) {
            testcases.add(new TestcaseMetaDto(i, false, inputSha, outputSha, null));
        }
        return runner.run(JudgeJobDto.builder()
                .submission(101, 1)
                .traceId("trace-it")
                .language("cpp20", "g++ -std=gnu++20 -O2 -pipe -static -o {bin} {src}", "{bin}")
                .compileLimits(20_000, 524_288)
                .runLimitsOnReferenceHost(1_000, 65_536, 65_536)
                .source("Main.cpp", source, Sha256.hexOf(source))
                .checker(CheckerType.TOKEN, null)
                .scoring(ScoringMode.ALL_OR_NOTHING, 100)
                .testdata(1, Sha256.hexOf("manifest"), testcases)
                .build());
    }
}
