package dev.oj.worker.run;

import dev.oj.contract.CheckerType;
import dev.oj.contract.JudgeJobDto;
import dev.oj.contract.ScoringMode;
import dev.oj.contract.Sha256;
import dev.oj.contract.TestcaseMetaDto;
import dev.oj.contract.Verdict;
import dev.oj.worker.config.WorkerProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ★ Điểm G của {@code build-order.md}: <b>M1 không thực thi một dòng mã người dùng nào.</b>
 *
 * <p>Luật ArchUnit 6 ({@code WorkerArchitectureTest}) chặn ở tầng kiểu — không package nào
 * ngoài {@code sandbox} được chạm {@code ProcessBuilder}. Test này canh mặt còn lại: hành vi.
 */
class ScriptedJudgeRunnerTest {

    private final ScriptedJudgeRunner runner = new ScriptedJudgeRunner(properties());

    @Test
    @DisplayName("★ mặc định là IE, KHÔNG phải AC — bản giả lọt lên host phải hỏng ồn ào")
    void bai_khong_mang_chi_thi_thi_nhan_IE() {
        var result = runner.run(job("int main(){ return 0; }"));

        assertThat(result.verdict()).isEqualTo(Verdict.IE);
        assertThat(result.score()).isZero();
        // Nếu mặc định là AC thì ngày bản giả chạy trên host thật, MỌI bài đều AC và hệ thống
        // trông vẫn "chạy bình thường" — mọi con số "đã giải bao nhiêu bài" thành vô nghĩa.
    }

    @Test
    void bon_kich_ban_tra_dung_verdict() {
        assertThat(runner.run(job("// EXPECT: AC\nint main(){}")).verdict()).isEqualTo(Verdict.AC);
        assertThat(runner.run(job("// EXPECT: WA\nint main(){}")).verdict()).isEqualTo(Verdict.WA);
        assertThat(runner.run(job("// EXPECT: CE\nint main(){}")).verdict()).isEqualTo(Verdict.CE);
        assertThat(runner.run(job("// EXPECT: TLE\nint main(){}")).verdict()).isEqualTo(Verdict.TLE);
    }

    /** Kịch bản CRASH tồn tại để chaos test có cách giết worker giữa chừng và kiểm reaper. */
    @Test
    void kich_ban_CRASH_nem_loi_de_test_reaper() {
        assertThatThrownBy(() -> runner.run(job("// EXPECT: CRASH\nint main(){}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CRASH");
    }

    /**
     * ★ Chỉ đọc dòng ĐẦU TIÊN. Quét cả file thì một bài nộp thật có chuỗi {@code EXPECT: AC}
     * trong comment ở giữa sẽ tự điều khiển được kết quả chấm của chính nó.
     */
    @Test
    void chi_thi_nam_giua_file_khong_co_tac_dung() {
        String source = """
                int main(){
                    // EXPECT: AC   <- không được tin dòng này
                    return 1;
                }
                """;

        assertThat(runner.run(job(source)).verdict()).isEqualTo(Verdict.IE);
    }

    @Test
    void AC_thi_diem_toi_da_va_khong_chi_ra_test_nao_sai() {
        var result = runner.run(job("// EXPECT: AC\n"));

        assertThat(result.score()).isEqualTo(result.maxScore());
        assertThat(result.failedTestOrdinal()).isNull();   // AC mà có test sai là mâu thuẫn
    }

    @Test
    void ket_qua_luon_mang_ten_may_va_he_so_de_con_truy_duoc_so_do() {
        var result = runner.run(job("// EXPECT: AC\n"));

        assertThat(result.hostName()).isEqualTo("may-test");
        assertThat(result.hostFactor()).isEqualByComparingTo("1.000");
        assertThat(result.startedAt()).isNotNull();
    }

    /** {@code toString()} của kết quả không được mang log compiler ra một dòng log. */
    @Test
    void toString_cua_ket_qua_khong_lo_log_compiler() {
        var result = runner.run(job("// EXPECT: CE\n"));

        assertThat(result.compileLog()).isNotBlank();
        assertThat(result.toString()).doesNotContain(result.compileLog());
    }

    @Test
    void parser_chiu_duoc_source_rong_va_null() {
        assertThat(ScriptedJudgeRunner.directiveOf(null)).isEqualTo("NONE");
        assertThat(ScriptedJudgeRunner.directiveOf("   ")).isEqualTo("NONE");
        assertThat(ScriptedJudgeRunner.directiveOf("// expect: ac")).isEqualTo("AC");
    }

    private static JudgeJobDto job(String source) {
        String sha = Sha256.hexOf(source == null ? "" : source);
        return JudgeJobDto.builder()
                .submission(101L, 1)
                .traceId("trace-test")
                .language("cpp20", "g++ -O2 -o {bin} {src}", "{bin}")
                .runLimitsOnReferenceHost(1000, 262_144, 65_536)
                .source("Main.cpp", source, sha)
                .checker(CheckerType.TOKEN, null)
                .scoring(ScoringMode.ALL_OR_NOTHING, 100)
                .testdata(1, sha, List.of(new TestcaseMetaDto(1, true, sha, sha, null)))
                .build();
    }

    private static WorkerProperties properties() {
        return dev.oj.worker.WorkerFixtures.properties(
                java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "oj-worker-test"));
    }
}
