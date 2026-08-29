package dev.oj.it;

import dev.oj.contract.ClaimRequestDto;
import dev.oj.contract.JudgeJobDto;
import dev.oj.contract.JudgeResultDto;
import dev.oj.contract.ScoringMode;
import dev.oj.contract.SubtaskResultDto;
import dev.oj.contract.SubtaskScoring;
import dev.oj.contract.SubtaskSpecDto;
import dev.oj.contract.Verdict;
import dev.oj.judging.application.usecase.ClaimJudgeJobUseCase;
import dev.oj.judging.application.usecase.RecordJudgeResultUseCase;
import dev.oj.judging.application.usecase.SubmitSolutionUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ Bước 3.3 · FR-PROB-06 — chấm theo nhóm, <b>cả hai chiều</b>, trên Postgres thật.
 *
 * <h2>Vì sao {@code SubtaskScorerTest} chưa đủ</h2>
 * Nó kiểm phép tính điểm, và kiểm rất kỹ — nhưng nó nhận một {@code JudgeJobDto} dựng sẵn
 * bằng tay. Ca này kiểm hai mảnh mà không unit test nào chạm tới:
 *
 * <ol>
 *   <li><b>Chiều đi</b> — {@code array_agg(...) FILTER (WHERE ... IS NOT NULL)} trong
 *       {@code JdbcJudgeSpecRepository} có thật sự gom được phụ thuộc không, và mảng
 *       {@code smallint[]} của Postgres có về Java đúng không (nó về là {@code Short[]},
 *       không phải {@code Integer[]} — một chỗ rất dễ sai và chỉ hỏng lúc chạy).</li>
 *   <li><b>Chiều về</b> — {@code judge_run_subtasks} có nhận đúng dữ liệu không, và nhóm
 *       <i>bị bỏ qua</i> ({@code verdict = null} trong hợp đồng) có thành chuỗi
 *       {@code 'SKIPPED'} trong một cột {@code NOT NULL} không.</li>
 * </ol>
 */
class SubtaskResultsIT extends PostgresIT {

    @Autowired SubmitSolutionUseCase submitSolution;
    @Autowired ClaimJudgeJobUseCase claimJudgeJob;
    @Autowired RecordJudgeResultUseCase recordResult;

    /**
     * Biến đề A+B thành một đề chia nhóm: test 1 vào nhóm 1 (40 điểm), test 2 và 3 vào nhóm 2
     * (60 điểm), và nhóm 2 phụ thuộc nhóm 1.
     *
     * <p>{@code PostgresIT.resetHotTables()} hoàn nguyên toàn bộ sau mỗi test — gỡ
     * {@code testcases.subtask_id} trước rồi mới xoá {@code subtasks}, đúng chiều khoá ngoại.
     */
    @BeforeEach
    void chiaDeThanhHaiNhom() {
        jdbc.sql("UPDATE problems SET scoring_mode = 'SUBTASK' WHERE id = :id")
                .param("id", PROBLEM_ID).update();
        jdbc.sql("""
                INSERT INTO subtasks (problem_id, testdata_version, ordinal, points, scoring)
                VALUES (:p, 1, 1, 40, 'MIN'), (:p, 1, 2, 60, 'SUM')
                """).param("p", PROBLEM_ID).update();
        jdbc.sql("""
                INSERT INTO subtask_dependencies (subtask_id, depends_on_subtask_id)
                SELECT sau.id, truoc.id
                  FROM subtasks sau, subtasks truoc
                 WHERE sau.problem_id = :p AND sau.ordinal = 2
                   AND truoc.problem_id = :p AND truoc.ordinal = 1
                """).param("p", PROBLEM_ID).update();
        jdbc.sql("""
                UPDATE testcases t
                   SET subtask_id = s.id
                  FROM subtasks s
                 WHERE s.problem_id = :p AND s.testdata_version = 1
                   AND t.problem_id = :p AND t.testdata_version = 1
                   AND s.ordinal = CASE WHEN t.ordinal = 1 THEN 1 ELSE 2 END
                """).param("p", PROBLEM_ID).update();
    }

    @Test
    @DisplayName("★ chiều đi — job mang đủ điểm nhóm, kiểu tính, và phụ thuộc")
    void job_mang_du_mo_ta_nhom() {
        submit();
        JudgeJobDto job = claim();

        assertThat(job.scoringMode()).isEqualTo(ScoringMode.SUBTASK);
        assertThat(job.maxScore())
                .as("API tính maxScore = tổng điểm nhóm; hợp đồng từ chối job nào lệch")
                .isEqualTo(100);

        assertThat(job.subtasks()).hasSize(2);
        SubtaskSpecDto nhom1 = job.subtasks().get(0);
        SubtaskSpecDto nhom2 = job.subtasks().get(1);

        assertThat(nhom1.points()).isEqualTo(40);
        assertThat(nhom1.scoring()).isEqualTo(SubtaskScoring.MIN);
        assertThat(nhom1.dependsOn())
                .as("array_agg + FILTER: nhóm không phụ thuộc gì phải ra mảng RỖNG, "
                        + "không phải {NULL}")
                .isEmpty();

        assertThat(nhom2.points()).isEqualTo(60);
        assertThat(nhom2.scoring()).isEqualTo(SubtaskScoring.SUM);
        assertThat(nhom2.dependsOn())
                .as("smallint[] của Postgres về Java là Short[], không phải Integer[]")
                .containsExactly(1);

        assertThat(job.testcases()).extracting(t -> Map.entry(t.ordinal(), t.subtaskOrdinal()))
                .containsExactly(Map.entry(1, 1), Map.entry(2, 2), Map.entry(3, 2));
    }

    @Test
    @DisplayName("★ chiều về — điểm từng nhóm xuống judge_run_subtasks, nhóm bỏ qua thành SKIPPED")
    void diem_tung_nhom_duoc_luu() {
        long id = submit();
        int attempt = claim().attempt();

        // Nhóm 1 sai ở test 1 → 0 điểm. Nhóm 2 phụ thuộc nhóm 1 nên KHÔNG được chấm.
        recordResult.record(new JudgeResultDto(id, attempt, Verdict.WA, 0, 100,
                1, 1, 15, 2048, null, "RE exit=1", "mac-m1max-host",
                new BigDecimal("1.000"), Instant.now(),
                List.of(new SubtaskResultDto(1, Verdict.WA, 0, 40, 1, 15, 2048),
                        SubtaskResultDto.skipped(2, 60))));

        var rows = jdbc.sql("""
                SELECT subtask_ordinal, verdict, score, max_score, failed_test_ordinal
                  FROM judge_run_subtasks
                 WHERE submission_id = :id AND attempt = :attempt
                 ORDER BY subtask_ordinal
                """).param("id", id).param("attempt", attempt).query().listOfRows();

        assertThat(rows).hasSize(2);
        // Bất đối xứng của pgjdbc, và nó có thật: cột SMALLINT vô hướng về Java là Integer,
        // nhưng PHẦN TỬ của một smallint[] thì về là Short. Đó chính là lý do
        // JdbcJudgeSpecRepository.dependsOn() ép qua Number thay vì cast thẳng Integer.
        assertThat(rows.get(0))
                .containsEntry("subtask_ordinal", 1)
                .containsEntry("verdict", "WA")
                .containsEntry("score", 0)
                .containsEntry("max_score", 40)
                .containsEntry("failed_test_ordinal", 1);

        assertThat(rows.get(1))
                .as("nhóm bỏ qua KHÁC nhóm 0 điểm: nó nói với thí sinh rằng sửa nhóm 1 thì "
                        + "nhóm 2 còn cửa. Cột NOT NULL nên hợp đồng null thành chuỗi SKIPPED")
                .containsEntry("verdict", "SKIPPED")
                .containsEntry("score", 0)
                .containsEntry("max_score", 60);
        assertThat(rows.get(1).get("failed_test_ordinal")).isNull();
    }

    /** Xoá lần chấm thì điểm nhóm đi theo — không để lại dòng mồ côi. */
    @Test
    @DisplayName("judge_run_subtasks biến mất cùng judge_runs (ON DELETE CASCADE)")
    void xoa_lan_cham_thi_diem_nhom_di_theo() {
        long id = submit();
        int attempt = claim().attempt();
        recordResult.record(new JudgeResultDto(id, attempt, Verdict.AC, 100, 100,
                null, 3, 20, 2048, null, "OK", "mac-m1max-host",
                new BigDecimal("1.000"), Instant.now(),
                List.of(new SubtaskResultDto(1, Verdict.AC, 40, 40, null, 10, 2048),
                        new SubtaskResultDto(2, Verdict.AC, 60, 60, null, 20, 2048))));
        assertThat(countSubtaskRows(id)).isEqualTo(2);

        jdbc.sql("DELETE FROM judge_runs WHERE submission_id = :id").param("id", id).update();

        assertThat(countSubtaskRows(id)).isZero();
    }

    private long submit() {
        return submitSolution.submit(new SubmitSolutionUseCase.Command(
                PROBLEM_ID, "cpp20", "// EXPECT: WA\nint main(){}")).submissionId();
    }

    private JudgeJobDto claim() {
        return claimJudgeJob.claim(ClaimRequestDto.single("mac-m1max-host", "arm64"))
                .orElseThrow(() -> new AssertionError("hàng đợi rỗng"));
    }

    private int countSubtaskRows(long submissionId) {
        return jdbc.sql("SELECT count(*)::int FROM judge_run_subtasks WHERE submission_id = :id")
                .param("id", submissionId).query(Integer.class).single();
    }
}
