package dev.oj.judging.domain;

import dev.oj.contract.JudgeResultDto;
import dev.oj.contract.Verdict;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class JudgeRunTest {

    private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");
    private static final BigDecimal HOST_FACTOR = new BigDecimal("1.000");
    private static final String HOST = "mac-m1max-host";

    private static JudgeRun run(int attempt, String compileLog) {
        return new JudgeRun(1L, attempt, HOST, HOST_FACTOR, 3, 5,
                new JudgeOutcome(Verdict.CE, 0, 100, null, null, null), 0,
                compileLog, null, "trace-abc", NOW, NOW.plusSeconds(1));
    }

    @Test
    void khong_ton_tai_ban_ghi_cham_nao_mang_attempt_0() {
        assertThatIllegalArgumentException().isThrownBy(() -> run(0, null));
        assertThat(run(DomainRules.FIRST_ATTEMPT, null).isFirstAttempt()).isTrue();
        assertThat(run(2, null).isFirstAttempt()).isFalse();
    }

    @Test
    void log_compiler_qua_dai_thi_cat_chu_khong_nem() {
        String log_dai = "x".repeat(JudgeResultDto.MAX_COMPILE_LOG_BYTES + 10_000);

        JudgeRun judgeRun = run(1, log_dai);

        assertThat(judgeRun.compileLog().getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(JudgeResultDto.MAX_COMPILE_LOG_BYTES);
        assertThat(judgeRun.compileLog()).endsWith("[đã cắt bớt]");
    }

    @Test
    void cat_khong_lam_doi_mot_ky_tu_utf8() {
        String log_tieng_viet = "ộ".repeat(JudgeResultDto.MAX_COMPILE_LOG_BYTES);

        String cat = run(1, log_tieng_viet).compileLog();

        assertThat(cat.getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(JudgeResultDto.MAX_COMPILE_LOG_BYTES);
        assertThat(cat).doesNotContain("�");
    }

    @Test
    void lech_dong_ho_giua_worker_va_api_khong_lam_hong_ket_qua() {
        assertThatCode(() -> new JudgeRun(1L, 1, HOST, HOST_FACTOR, 3, 5,
                new JudgeOutcome(Verdict.AC, 100, 100, null, 120, 2048), 50,
                null, null, "trace-abc", NOW.plusSeconds(2), NOW))
                .doesNotThrowAnyException();
    }

    @Test
    void host_factor_phai_duong_vi_con_so_thoi_gian_quy_chieu_ve_no() {
        assertThatIllegalArgumentException().isThrownBy(() -> new JudgeRun(
                1L, 1, HOST, BigDecimal.ZERO, 3, 5,
                new JudgeOutcome(Verdict.AC, 100, 100, null, 120, 2048), 50,
                null, null, null, NOW, NOW));
    }
}
