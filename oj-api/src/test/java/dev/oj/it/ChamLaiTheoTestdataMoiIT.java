package dev.oj.it;

import dev.oj.contract.ClaimRequestDto;
import dev.oj.contract.JudgeJobDto;
import dev.oj.contract.JudgeResultDto;
import dev.oj.contract.Verdict;
import dev.oj.judging.application.RejudgeJobHandler;
import dev.oj.judging.application.usecase.ClaimJudgeJobUseCase;
import dev.oj.judging.application.usecase.RecordJudgeResultUseCase;
import dev.oj.judging.application.usecase.RecordJudgeResultUseCase.Outcome;
import dev.oj.platform.jobs.JobsException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChamLaiTheoTestdataMoiIT extends PostgresIT {

    private static final ClaimRequestDto MAY = ClaimRequestDto.single("mac-m1max-host", "arm64");

    @Autowired RejudgeJobHandler handler;
    @Autowired ClaimJudgeJobUseCase claimJudgeJob;
    @Autowired RecordJudgeResultUseCase recordJudgeResult;

    @Test
    @DisplayName("★ CL-13 · đề đổi testdata 1 → 2 rồi chấm lại: job mang phiên bản 2, judge_runs ghi 2, lượt cũ vẫn ghi 1")
    void cham_lai_dung_testdata_moi() {
        long de = taoDeCoHaiPhienBan();
        long bai = baiDaChamOPhienBanMot(de);
        jdbc.sql("""
                UPDATE problems SET current_testdata_version = 2 WHERE id = :id
                """).param("id", de).update();

        try {
            handler.chay(new JobContextGia(Map.of(RejudgeJobHandler.THAM_SO_DE, de), ADMIN_ID));
        } catch (JobsException e) {
            assertThat(e.code()).isEqualTo("job.tam_nghi");
        }

        JudgeJobDto job = claimJudgeJob.claim(MAY).orElseThrow();
        assertThat(job.submissionId()).isEqualTo(bai);
        assertThat(job.testdataVersion())
                .as("chấm lại sau khi sửa testdata phải chạy bộ test MỚI — FR-PROB-10")
                .isEqualTo(2);
        assertThat(job.testcases()).hasSize(2);

        assertThat(recordJudgeResult.record(new JudgeResultDto(bai, job.attempt(), Verdict.AC,
                100, 100, null, 2, 23, 8192, null, null, "mac-m1max-host",
                new BigDecimal("1.000"), Instant.now(), List.of()))).isEqualTo(Outcome.RECORDED);

        assertThat(jdbc.sql("""
                SELECT attempt, testdata_version FROM judge_runs
                 WHERE submission_id = :id ORDER BY attempt
                """).param("id", bai).query((rs, n) -> rs.getInt(1) + ":" + rs.getInt(2)).list())
                .as("lịch sử giữ phiên bản của từng lượt")
                .containsExactly("1:1", "2:2");
    }

    private long taoDeCoHaiPhienBan() {
        long de = jdbc.sql("""
                INSERT INTO problems (code, title, statement_md, statement_hash, time_limit_ms,
                                      memory_limit_kb, output_limit_kb, checker_type,
                                      scoring_mode, feedback_level, status, owner_id,
                                      published_at, current_testdata_version)
                VALUES ('DOI-TEST', 'Đổi test', 'x', :hash, 1000, 262144, 65536, 'token',
                        'ALL_OR_NOTHING', 'TEST_INDEX', 'PUBLISHED', :chu, now(), 1)
                RETURNING id
                """).param("hash", "f".repeat(64)).param("chu", SETTER_ID)
                .query(Long.class).single();
        jdbc.sql("""
                INSERT INTO testdata_versions (problem_id, version, manifest_sha256, test_count,
                                               total_bytes, created_by)
                VALUES (:de, 1, :m1, 1, 6, :chu), (:de, 2, :m2, 2, 12, :chu)
                """).param("de", de).param("m1", "1".repeat(64)).param("m2", "2".repeat(64))
                .param("chu", SETTER_ID).update();
        jdbc.sql("""
                INSERT INTO testcases (problem_id, testdata_version, ordinal, is_sample,
                                       input_sha256, output_sha256, input_bytes, output_bytes)
                VALUES (:de, 1, 1, FALSE, :a, :b, 3, 3),
                       (:de, 2, 1, FALSE, :a, :b, 3, 3),
                       (:de, 2, 2, FALSE, :c, :d, 3, 3)
                """).param("de", de).param("a", "a".repeat(64)).param("b", "b".repeat(64))
                .param("c", "c".repeat(64)).param("d", "d".repeat(64)).update();
        return de;
    }

    private long baiDaChamOPhienBanMot(long de) {
        jdbc.sql("""
                INSERT INTO source_blobs (sha256, content, byte_size)
                VALUES (:sha, 'int main(){}', 12) ON CONFLICT DO NOTHING
                """).param("sha", "e".repeat(64)).update();
        long bai = jdbc.sql("""
                INSERT INTO submissions (user_id, problem_id, language_id, source_sha256,
                                         source_bytes, testdata_version, status, attempt,
                                         verdict, score, max_score, judged_at)
                VALUES (:u, :de, 1, :sha, 12, 1, 'DONE', 1, 'AC', 100, 100, now())
                RETURNING id
                """).param("u", USER_ID).param("de", de).param("sha", "e".repeat(64))
                .query(Long.class).single();
        jdbc.sql("""
                INSERT INTO judge_runs (submission_id, attempt, host_factor, language_id,
                                        testdata_version, verdict, score, max_score, tests_run,
                                        started_at, finished_at)
                VALUES (:id, 1, 1.000, 1, 1, 'AC', 100, 100, 1, now(), now())
                """).param("id", bai).update();
        return bai;
    }
}
