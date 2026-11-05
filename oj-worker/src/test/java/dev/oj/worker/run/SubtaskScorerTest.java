package dev.oj.worker.run;

import dev.oj.contract.CheckerType;
import dev.oj.contract.JudgeJobDto;
import dev.oj.contract.ScoringMode;
import dev.oj.contract.Sha256;
import dev.oj.contract.SubtaskScoring;
import dev.oj.contract.SubtaskSpecDto;
import dev.oj.contract.TestcaseMetaDto;
import dev.oj.contract.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SubtaskScorerTest {

    private static final String SHA = Sha256.hexOf("x");

    private static final class RecordingExecutor implements SubtaskScorer.TestExecutor {

        private final Set<Integer> failing;
        final List<Integer> ran = new ArrayList<>();

        RecordingExecutor(Set<Integer> failing) {
            this.failing = failing;
        }

        @Override
        public TestRunner.TestOutcome run(TestcaseMetaDto testcase) {
            ran.add(testcase.ordinal());
            return new TestRunner.TestOutcome(
                    failing.contains(testcase.ordinal()) ? Verdict.WA : Verdict.AC, 5, 1024, "OK");
        }
    }

    @Test
    @DisplayName("★ MIN — đúng hết cả hai nhóm thì trọn điểm và verdict AC")
    void min_dung_het() {
        var executor = new RecordingExecutor(Set.of());
        var result = SubtaskScorer.score(job(SubtaskScoring.MIN), executor);

        assertThat(result.score()).isEqualTo(100);
        assertThat(result.verdict()).isEqualTo(Verdict.AC);
        assertThat(result.failedOrdinal()).isNull();
        assertThat(executor.ran).containsExactly(1, 2, 3, 4);
    }

    @Test
    @DisplayName("★ MIN — sai test 1 thì bỏ nốt nhóm 1, NHƯNG vẫn chấm nhóm 2")
    void min_sai_thi_bo_not_nhom_do_nhung_van_cham_nhom_sau() {
        var executor = new RecordingExecutor(Set.of(1));
        var result = SubtaskScorer.score(job(SubtaskScoring.MIN), executor);

        assertThat(executor.ran)
                .as("test 2 cùng nhóm với test 1 nên vô ích; nhóm 2 thì không")
                .containsExactly(1, 3, 4);
        assertThat(result.score()).isEqualTo(60);
        assertThat(result.verdict()).isEqualTo(Verdict.WA);
        assertThat(result.failedOrdinal()).isEqualTo(1);
        assertThat(result.subtasks()).hasSize(2);
        assertThat(result.subtasks().get(0).score()).isZero();
        assertThat(result.subtasks().get(1).score()).isEqualTo(60);
    }

    @Test
    @DisplayName("SUM — nửa nhóm đúng thì nửa điểm, và mọi test đều phải chạy")
    void sum_cong_diem_tung_test() {
        var executor = new RecordingExecutor(Set.of(2));
        var result = SubtaskScorer.score(job(SubtaskScoring.SUM), executor);

        assertThat(executor.ran).containsExactly(1, 2, 3, 4);
        assertThat(result.subtasks().get(0).score()).isEqualTo(20);
        assertThat(result.score()).isEqualTo(80);
    }

    @Test
    @DisplayName("★ phụ thuộc không đạt thì nhóm sau KHÔNG CHẠY, và được đánh dấu bỏ qua")
    void phu_thuoc_khong_dat_thi_bo_qua() {
        var executor = new RecordingExecutor(Set.of(1));
        var result = SubtaskScorer.score(jobWithDependency(), executor);

        assertThat(executor.ran)
                .as("test 3 và 4 thuộc nhóm 2, mà nhóm 2 phụ thuộc nhóm 1 đã hỏng")
                .containsExactly(1);

        var group2 = result.subtasks().get(1);
        assertThat(group2.isSkipped())
                .as("bỏ qua KHÁC 0 điểm: nó nói với thí sinh rằng sửa nhóm 1 thì nhóm 2 có cửa")
                .isTrue();
        assertThat(group2.score()).isZero();
        assertThat(group2.maxScore()).isEqualTo(60);
        assertThat(result.testsRun())
                .as("test của nhóm bị bỏ qua không được tính là đã chạy")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("phụ thuộc đạt trọn điểm thì nhóm sau được chấm bình thường")
    void phu_thuoc_dat_thi_cham_tiep() {
        var executor = new RecordingExecutor(Set.of());
        var result = SubtaskScorer.score(jobWithDependency(), executor);

        assertThat(executor.ran).containsExactly(1, 2, 3, 4);
        assertThat(result.score()).isEqualTo(100);
        assertThat(result.verdict()).isEqualTo(Verdict.AC);
    }

    @Test
    @DisplayName("★ AC khi và chỉ khi TRỌN điểm")
    void AC_chi_khi_tron_diem() {
        var result = SubtaskScorer.score(job(SubtaskScoring.SUM),
                new RecordingExecutor(Set.of(4)));

        assertThat(result.score()).isLessThan(100);
        assertThat(result.verdict()).isNotEqualTo(Verdict.AC);
    }

    private static JudgeJobDto job(SubtaskScoring scoring) {
        return baseJob(List.of(
                new SubtaskSpecDto(1, 40, scoring, List.of()),
                new SubtaskSpecDto(2, 60, scoring, List.of())));
    }

    private static JudgeJobDto jobWithDependency() {
        return baseJob(List.of(
                new SubtaskSpecDto(1, 40, SubtaskScoring.MIN, List.of()),
                new SubtaskSpecDto(2, 60, SubtaskScoring.MIN, List.of(1))));
    }

    private static JudgeJobDto baseJob(List<SubtaskSpecDto> subtasks) {
        return JudgeJobDto.builder()
                .submission(1L, 1)
                .traceId("t")
                .language("cpp20", "g++ -o {bin} {src}", "{bin}")
                .runLimitsOnReferenceHost(1000, 262_144, 65_536)
                .source("Main.cpp", "int main(){}", SHA)
                .checker(CheckerType.TOKEN, null)
                .scoring(ScoringMode.SUBTASK, 100)
                .subtasks(subtasks)
                .testdata(1, SHA, List.of(
                        new TestcaseMetaDto(1, false, SHA, SHA, 1),
                        new TestcaseMetaDto(2, false, SHA, SHA, 1),
                        new TestcaseMetaDto(3, false, SHA, SHA, 2),
                        new TestcaseMetaDto(4, false, SHA, SHA, 2)))
                .build();
    }
}
