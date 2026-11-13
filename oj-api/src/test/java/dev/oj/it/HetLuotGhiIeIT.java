package dev.oj.it;

import dev.oj.contract.ClaimRequestDto;
import dev.oj.contract.JudgeJobDto;
import dev.oj.contract.JudgeResultDto;
import dev.oj.contract.Verdict;
import dev.oj.judging.application.usecase.ClaimJudgeJobUseCase;
import dev.oj.judging.application.usecase.ReapStaleJobsUseCase;
import dev.oj.judging.application.usecase.RecordJudgeResultUseCase;
import dev.oj.judging.application.usecase.RecordJudgeResultUseCase.Outcome;
import dev.oj.judging.application.usecase.SubmitSolutionUseCase;
import dev.oj.platform.config.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HetLuotGhiIeIT extends PostgresIT {

    private static final ClaimRequestDto MAY = ClaimRequestDto.single("mac-m1max-host", "arm64");
    private static final String TEN_MAY = "mac-m1max-host";
    private static final int PHIEN_BAN_KHONG_TON_TAI = 999;

    @Autowired SubmitSolutionUseCase submitSolution;
    @Autowired ClaimJudgeJobUseCase claimJudgeJob;
    @Autowired RecordJudgeResultUseCase recordJudgeResult;
    @Autowired ReapStaleJobsUseCase reapStaleJobs;
    @Autowired AppProperties properties;

    @Test
    @DisplayName("★ KQ-07 · ba IE liên tiếp trên Postgres thật: hai lần đầu bài quay lại hàng đợi, lần thứ ba mới ghi verdict IE")
    void ba_ie_lien_tiep() {
        long id = nop();
        int luotTuChamLai = properties.judge().maxIeRetries();

        for (int lan = 1; lan <= luotTuChamLai; lan++) {
            int attempt = claimJudgeJob.claim(MAY).orElseThrow().attempt();
            assertThat(attempt).isEqualTo(lan);
            assertThat(recordJudgeResult.record(ie(id, attempt)))
                    .as("IE lần %d: còn lượt tự chấm lại", lan)
                    .isEqualTo(Outcome.IE_RETRY_SCHEDULED);
            assertThat(ieRetryCount(id)).isEqualTo(lan);
            assertThat(dangGiuLease(id)).as("bài quay lại hàng đợi, không ai giữ").isFalse();
            assertThat(verdict(id)).as("người nộp chưa thấy IE").isNull();
            assertThat(soBanGhiCham(id)).isZero();
        }

        int cuoi = claimJudgeJob.claim(MAY).orElseThrow().attempt();
        assertThat(recordJudgeResult.record(ie(id, cuoi))).isEqualTo(Outcome.RECORDED);

        assertThat(cuoi).isEqualTo(luotTuChamLai + 1);
        assertThat(status(id)).isEqualTo("DONE");
        assertThat(verdict(id)).isEqualTo("IE");
        assertThat(soBanGhiCham(id)).isEqualTo(1);
        assertThat(soDongHangDoi(id)).isZero();
    }

    @Test
    @DisplayName("★ CL-06 · đề mất phiên bản testdata đã ghim: claim trả rỗng mà vẫn giữ lease, bài sau vẫn được giao, hết lượt thả lại thì IE")
    void de_mat_testdata_thi_het_luot_ghi_ie() {
        long hong = nop();
        jdbc.sql("""
                UPDATE submissions SET testdata_version = :v WHERE id = :id
                """).param("v", PHIEN_BAN_KHONG_TON_TAI).param("id", hong).update();
        quenLuotNopVuaRoi(USER_ID);
        long lanh = nop();

        assertThat(claimJudgeJob.claim(MAY)).as("bài hỏng: không dựng được job").isEmpty();
        assertThat(dangGiuLease(hong)).as("nhưng vẫn giữ lease, để reaper đếm lượt").isTrue();
        JudgeJobDto sau = claimJudgeJob.claim(MAY).orElseThrow();
        assertThat(sau.submissionId()).as("bài sau không bị bài hỏng chặn").isEqualTo(lanh);
        assertThat(recordJudgeResult.record(ac(lanh, sau.attempt()))).isEqualTo(Outcome.RECORDED);

        int maxReaps = properties.judge().maxReaps();
        for (int lan = 1; lan <= maxReaps; lan++) {
            hetLease(hong);
            assertThat(reapStaleJobs.reap()).isEqualTo(1);
            assertThat(status(hong)).as("lần thả lại thứ %d", lan).isEqualTo("QUEUED");
            assertThat(claimJudgeJob.claim(MAY)).isEmpty();
        }
        hetLease(hong);
        assertThat(reapStaleJobs.reap()).isEqualTo(1);

        assertThat(status(hong)).as("hết lượt thả lại thì bỏ cuộc").isEqualTo("DONE");
        assertThat(verdict(hong)).isEqualTo("IE");
        assertThat(soBanGhiCham(hong)).isEqualTo(1);
        assertThat(soDongHangDoi(hong)).isZero();
        assertThat(verdict(lanh)).isEqualTo("AC");
    }

    private long nop() {
        return submitSolution.submit(new SubmitSolutionUseCase.Command(
                PROBLEM_ID, "cpp20", "// EXPECT: AC\nint main(){}")).submissionId();
    }

    private static JudgeResultDto ie(long id, int attempt) {
        return JudgeResultDto.internalError(id, attempt, TEN_MAY, new BigDecimal("1.000"),
                Instant.now(), "kiem-thu-IE");
    }

    private static JudgeResultDto ac(long id, int attempt) {
        return new JudgeResultDto(id, attempt, Verdict.AC, 100, 100, null, 3, 23, 8192,
                null, null, TEN_MAY, new BigDecimal("1.000"), Instant.now(), List.of());
    }

    private void hetLease(long id) {
        jdbc.sql("""
                UPDATE judge_queue SET lease_until = now() - interval '1 second'
                 WHERE submission_id = :id
                """).param("id", id).update();
    }

    private String status(long id) {
        return jdbc.sql("""
                SELECT status FROM submissions WHERE id = :id
                """).param("id", id).query(String.class).single();
    }

    private String verdict(long id) {
        return jdbc.sql("""
                SELECT verdict FROM submissions WHERE id = :id
                """).param("id", id).query(String.class).optional().orElse(null);
    }

    private int ieRetryCount(long id) {
        return jdbc.sql("""
                SELECT ie_retry_count FROM judge_queue WHERE submission_id = :id
                """).param("id", id).query(Integer.class).single();
    }

    private boolean dangGiuLease(long id) {
        return jdbc.sql("""
                SELECT claimed_at IS NOT NULL FROM judge_queue WHERE submission_id = :id
                """).param("id", id).query(Boolean.class).single();
    }

    private int soDongHangDoi(long id) {
        return jdbc.sql("""
                SELECT count(*)::int FROM judge_queue WHERE submission_id = :id
                """).param("id", id).query(Integer.class).single();
    }

    private int soBanGhiCham(long id) {
        return jdbc.sql("""
                SELECT count(*)::int FROM judge_runs WHERE submission_id = :id
                """).param("id", id).query(Integer.class).single();
    }
}
