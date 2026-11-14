package dev.oj.worker.run;

import dev.oj.contract.CheckerType;
import dev.oj.contract.JudgeJobDto;
import dev.oj.contract.JudgeResultDto;
import dev.oj.contract.ScoringMode;
import dev.oj.contract.Sha256;
import dev.oj.contract.TestcaseMetaDto;
import dev.oj.contract.Verdict;
import dev.oj.worker.WorkerFixtures;
import dev.oj.worker.calibration.HostBenchmark;
import dev.oj.worker.client.JudgeApiClient;
import dev.oj.worker.config.WorkerProperties;
import dev.oj.worker.pipeline.JobExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IsolateJudgeRunnerLoiTest {

    private static final String BI_MAT = "dap-an-test-an-42-17";

    @Test
    @DisplayName("★ CB-14 · bộ chấm ném ngoại lệ → IE, và trạng thái gửi về API chỉ có tên lớp, không có thông điệp")
    void ngoai_le_thanh_ie_chi_mang_ten_lop() {
        WorkerProperties props = WorkerFixtures.properties(
                Path.of(System.getProperty("java.io.tmpdir"), "oj-worker-test"));
        var benchmark = new HostBenchmark(props, new JudgeApiClient(props, RestClient.builder()));
        JobExecutor hong = new JobExecutor(props, null, null, null, benchmark, null) {
            @Override
            public JudgeResultDto execute(JudgeJobDto job, String sourceFileName, Instant startedAt) {
                throw new IllegalStateException("đọc hỏng " + BI_MAT);
            }
        };

        JudgeResultDto kq = new IsolateJudgeRunner(hong, props, benchmark).run(job());

        assertThat(kq.verdict()).isEqualTo(Verdict.IE);
        assertThat(kq.isolateStatus()).isEqualTo("IllegalStateException");
        assertThat(kq.toString() + kq.isolateStatus() + kq.compileLog())
                .as("thông điệp ngoại lệ có thể mang dữ liệu test — không được rời worker (bất biến #1)")
                .doesNotContain(BI_MAT);
    }

    private static JudgeJobDto job() {
        String source = "int main(){}";
        String sha = Sha256.hexOf(source);
        return JudgeJobDto.builder()
                .submission(101L, 1)
                .traceId("trace-test")
                .language("cpp20", "g++ -O2 -o {bin} {src}", "{bin}")
                .runLimitsOnReferenceHost(1000, 262_144, 65_536)
                .source("Main.cpp", source, sha)
                .checker(CheckerType.TOKEN, null)
                .scoring(ScoringMode.ALL_OR_NOTHING, 100)
                .testdata(1, sha, List.of(new TestcaseMetaDto(1, false, sha, sha, null)))
                .build();
    }
}
