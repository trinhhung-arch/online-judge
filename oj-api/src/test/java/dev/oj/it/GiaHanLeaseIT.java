package dev.oj.it;

import dev.oj.contract.ClaimRequestDto;
import dev.oj.contract.JudgeProgressDto;
import dev.oj.contract.JudgeResultDto;
import dev.oj.contract.Verdict;
import dev.oj.judging.application.usecase.ClaimJudgeJobUseCase;
import dev.oj.judging.application.usecase.ReapStaleJobsUseCase;
import dev.oj.judging.application.usecase.RecordJudgeProgressUseCase;
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

class GiaHanLeaseIT extends PostgresIT {

    private static final ClaimRequestDto REQUEST =
            ClaimRequestDto.single("mac-m1max-host", "arm64");

    @Autowired SubmitSolutionUseCase submitSolution;
    @Autowired ClaimJudgeJobUseCase claimJudgeJob;
    @Autowired RecordJudgeProgressUseCase recordProgress;
    @Autowired RecordJudgeResultUseCase recordJudgeResult;
    @Autowired ReapStaleJobsUseCase reapStaleJobs;
    @Autowired AppProperties properties;

    @Test
    @DisplayName("★ ADR 026 · bài chấm lâu gấp ba lease mà vẫn báo tiến độ → không bị thu hồi, kết quả được nhận ở attempt 1")
    void bai_cham_lau_nhung_con_song_thi_khong_bi_thu_hoi() {
        long id = submit();
        int attempt = claimJudgeJob.claim(REQUEST).orElseThrow().attempt();

        for (int lo = 1; lo <= 3; lo++) {
            leaseConLai(id, 1);
            recordProgress.record(tienDo(id, attempt, lo * 20));

            assertThat(giayConLaiCuaLease(id))
                    .as("lô %d phải đưa lease về lại gần đủ %ds", lo, lease())
                    .isBetween(lease() - 5.0, (double) lease());
            assertThat(reapStaleJobs.reap()).isZero();
        }

        assertThat(recordJudgeResult.record(ketQua(id, attempt))).isEqualTo(Outcome.RECORDED);
        assertThat(status(id)).isEqualTo("DONE");
        assertThat(jdbc.sql("SELECT attempt FROM submissions WHERE id = :id").param("id", id)
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ worker im lặng thì vẫn bị thu hồi sau đúng một lease — gia hạn không che worker chết")
    void im_lang_thi_van_bi_thu_hoi() {
        long id = submit();
        int attempt = claimJudgeJob.claim(REQUEST).orElseThrow().attempt();
        recordProgress.record(tienDo(id, attempt, 20));

        hetLease(id);

        assertThat(reapStaleJobs.reap()).isEqualTo(1);
        assertThat(status(id)).isEqualTo("QUEUED");
    }

    @Test
    @DisplayName("★ lượt đã bị thu hồi không gia hạn được — dòng hàng đợi vẫn tự do cho máy khác nhận")
    void luot_da_bi_thu_hoi_khong_gia_han_duoc() {
        long id = submit();
        int cu = claimJudgeJob.claim(REQUEST).orElseThrow().attempt();
        hetLease(id);
        assertThat(reapStaleJobs.reap()).isEqualTo(1);

        recordProgress.record(tienDo(id, cu, 40));

        assertThat(jdbc.sql("""
                SELECT claimed_at IS NULL AND lease_until IS NULL FROM judge_queue
                 WHERE submission_id = :id
                """).param("id", id).query(Boolean.class).single())
                .as("worker cũ không được tự nhận lại bài bằng một lô tiến độ").isTrue();
        assertThat(claimJudgeJob.claim(REQUEST).orElseThrow().attempt()).isEqualTo(cu + 1);
    }

    @Test
    @DisplayName("★ lượt cũ không giữ sống được lượt mới — tiến độ attempt 1 không đụng lease của attempt 2")
    void luot_cu_khong_giu_song_luot_moi() {
        long id = submit();
        int cu = claimJudgeJob.claim(REQUEST).orElseThrow().attempt();
        hetLease(id);
        reapStaleJobs.reap();
        int moi = claimJudgeJob.claim(REQUEST).orElseThrow().attempt();
        leaseConLai(id, 1);

        recordProgress.record(tienDo(id, cu, 60));
        assertThat(giayConLaiCuaLease(id)).isLessThanOrEqualTo(1.0);

        recordProgress.record(tienDo(id, moi, 20));
        assertThat(giayConLaiCuaLease(id)).isGreaterThan(lease() - 5.0);
    }

    @Test
    @DisplayName("lease vừa hết mà reaper chưa chạy: tiến độ tới trước thì thắng, như kết quả tới trước (ADR 025)")
    void tien_do_toi_truoc_reaper_thi_thang() {
        long id = submit();
        int attempt = claimJudgeJob.claim(REQUEST).orElseThrow().attempt();
        hetLease(id);

        recordProgress.record(tienDo(id, attempt, 20));

        assertThat(reapStaleJobs.reap()).isZero();
        assertThat(status(id)).isEqualTo("JUDGING");
    }

    private long submit() {
        return submitSolution.submit(new SubmitSolutionUseCase.Command(
                PROBLEM_ID, "cpp20", "// EXPECT: AC\nint main(){}")).submissionId();
    }

    private int lease() {
        return properties.judge().leaseSeconds();
    }

    private void hetLease(long id) {
        leaseConLai(id, -1);
    }

    private void leaseConLai(long id, int giay) {
        jdbc.sql("""
                UPDATE judge_queue SET lease_until = now() + make_interval(secs => :giay)
                 WHERE submission_id = :id
                """).param("giay", giay).param("id", id).update();
    }

    private double giayConLaiCuaLease(long id) {
        return jdbc.sql("""
                SELECT extract(epoch FROM lease_until - now()) FROM judge_queue
                 WHERE submission_id = :id
                """).param("id", id).query(Double.class).single();
    }

    private String status(long id) {
        return jdbc.sql("SELECT status FROM submissions WHERE id = :id").param("id", id)
                .query(String.class).single();
    }

    private static JudgeProgressDto tienDo(long id, int attempt, int toOrdinal) {
        return new JudgeProgressDto(id, attempt, toOrdinal, toOrdinal, 100,
                List.of(new JudgeProgressDto.TestOutcome(toOrdinal, Verdict.AC, 12, 2048)));
    }

    private static JudgeResultDto ketQua(long id, int attempt) {
        return new JudgeResultDto(id, attempt, Verdict.AC, 100, 100, null, 3, 23, 8192,
                null, null, "mac-m1max-host", new BigDecimal("1.000"), Instant.now(), List.of());
    }
}
