package dev.oj.judging.application.usecase;

import dev.oj.contract.JudgeResultDto;
import dev.oj.contract.Verdict;
import dev.oj.platform.error.DomainException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecordJudgeResultUseCaseTest {

    private JudgingFakes fakes;
    private RecordJudgeResultUseCase useCase;

    private JudgingFakes.FakeEventBus events;

    @BeforeEach
    void setUp() {
        fakes = new JudgingFakes();
        events = new JudgingFakes.FakeEventBus();
        useCase = new RecordJudgeResultUseCase(fakes.queue, fakes.judgeRuns, fakes.submissions,
                JudgingFakes.properties(), JudgingFakes.CLOCK, events, fakes.publisher);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("★ ghi verdict xong thì đẩy sự kiện realtime")
    void ghi_verdict_thi_day_su_kien() {
        useCase.record(result(Verdict.AC, 1));

        org.assertj.core.api.Assertions.assertThat(events.published).hasSize(1);
        var event = events.published.get(0);
        org.assertj.core.api.Assertions.assertThat(event.submissionId()).isEqualTo(101L);
        org.assertj.core.api.Assertions.assertThat(event.verdict()).isEqualTo("AC");
        org.assertj.core.api.Assertions.assertThat(event.isTerminal()).isTrue();
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("★ SEC3 — kiểu sự kiện SSE không có chỗ nào để chứa chi tiết test")
    void su_kien_khong_co_cho_chua_chi_tiet_test() {
        var forbidden = java.util.List.of("failed", "test", "ordinal", "compile", "log",
                "input", "output", "expected", "source");
        var fields = java.util.Arrays.stream(
                        dev.oj.judging.application.port.SubmissionEventBus.SubmissionEvent.class
                                .getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .map(name -> name.toLowerCase(java.util.Locale.ROOT))
                .toList();

        var explicitlyAllowed = java.util.Set.of("testsdone", "totaltests");

        for (String name : fields) {
            if (explicitlyAllowed.contains(name)) {
                continue;
            }
            for (String bad : forbidden) {
                org.assertj.core.api.Assertions.assertThat(name)
                        .as("trường '%s' của SubmissionEvent đi THẲNG ra trình duyệt, không qua "
                                + "bộ lọc feedback_level. Chi tiết phải đi qua "
                                + "GET /submissions/{id} (bất biến #1)", name)
                        .doesNotContain(bad);
            }
        }
        org.assertj.core.api.Assertions.assertThat(fields)
                .contains("submissionid", "status", "verdict");
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("kết quả trùng bị từ chối thì KHÔNG đẩy sự kiện nào")
    void ket_qua_trung_khong_day_su_kien() {
        useCase.record(result(Verdict.AC, 1));
        fakes.queue.lockWins = false;
        useCase.record(result(Verdict.WA, 1));

        org.assertj.core.api.Assertions.assertThat(events.published)
                .as("sự kiện thứ hai sẽ làm trang hiện WA cho một bài đã AC")
                .hasSize(1);
    }

    private JudgeResultDto result(Verdict verdict, int attempt) {
        return new JudgeResultDto(101L, attempt, verdict, verdict.isAccepted() ? 100 : 0, 100,
                verdict.isAccepted() ? null : 7, 20, 230, 4096, null, null,
                "mac-m1max-host", JudgingFakes.hostFactor(), JudgingFakes.NOW, List.of());
    }

    @Test
    @DisplayName("khoá lạc quan là câu ĐẦU TIÊN, và nó thắng thì mới có hai câu ghi sau")
    void duong_ghi_verdict_binh_thuong() {
        var outcome = useCase.record(result(Verdict.WA, 1));

        assertThat(outcome).isEqualTo(RecordJudgeResultUseCase.Outcome.RECORDED);
        assertThat(fakes.calls).containsExactly(
                "queue.releaseWithOptimisticLock",
                "judgeRuns.insertIfAbsent",
                "submissions.markDone");
    }

    @Test
    @DisplayName("★ KQ-04 · markDone cập nhật 0 dòng thì ném lỗi để rollback, không im lặng xoá bài khỏi hàng đợi")
    void markDone_khong_khop_thi_rollback() {
        fakes.submissions.markDoneKhop = false;

        assertThatThrownBy(() -> useCase.record(result(Verdict.AC, 1)))
                .hasFieldOrPropertyWithValue("kind", DomainException.Kind.CONFLICT)
                .hasFieldOrPropertyWithValue("code", "submission.trang_thai_khong_khop");
        assertThat(events.published)
                .as("không có sự kiện DONE cho một verdict không được ghi").isEmpty();
    }

    @Test
    void lock_tu_choi_thi_khong_mot_cau_ghi_nao_chay() {
        fakes.queue.lockWins = false;

        var outcome = useCase.record(result(Verdict.AC, 1));

        assertThat(outcome).isEqualTo(RecordJudgeResultUseCase.Outcome.IGNORED);
        assertThat(fakes.calls).containsExactly("queue.releaseWithOptimisticLock");
        assertThat(fakes.judgeRuns.inserted).isNull();
        assertThat(fakes.submissions.doneOutcome).isNull();
    }

    @Test
    void goi_hai_lan_thi_verdict_khong_doi() {
        useCase.record(result(Verdict.WA, 1));
        var first = fakes.submissions.doneOutcome;

        fakes.queue.lockWins = false;
        var second = useCase.record(result(Verdict.AC, 1));

        assertThat(second).isEqualTo(RecordJudgeResultUseCase.Outcome.IGNORED);
        assertThat(fakes.submissions.doneOutcome).isSameAs(first);
        assertThat(fakes.submissions.doneOutcome.verdict()).isEqualTo(Verdict.WA);
    }

    @Test
    void IE_con_luot_thi_khong_ghi_verdict_va_khong_dung_toi_khoa_lac_quan() {
        fakes.queue.ieRetryAccepted = true;

        var outcome = useCase.record(result(Verdict.IE, 1));

        assertThat(outcome).isEqualTo(RecordJudgeResultUseCase.Outcome.IE_RETRY_SCHEDULED);
        assertThat(fakes.calls).containsExactly("queue.retryIe", "events.publishEnqueued");
        assertThat(events.published).as("trình duyệt phải biết bài quay về hàng đợi")
                .singleElement().satisfies(e -> {
                    assertThat(e.status()).isEqualTo("QUEUED");
                    assertThat(e.attempt()).isEqualTo(1);
                    assertThat(e.verdict()).isNull();
                });
    }

    @Test
    void IE_chuong_hong_thi_bai_van_quay_lai_hang_doi() {
        fakes.queue.ieRetryAccepted = true;
        fakes.publisher.explode = true;

        var outcome = useCase.record(result(Verdict.IE, 1));

        assertThat(outcome).isEqualTo(RecordJudgeResultUseCase.Outcome.IE_RETRY_SCHEDULED);
        assertThat(fakes.calls).contains("queue.retryIe");
        assertThat(events.published).extracting(e -> e.status())
                .as("chuông hỏng không được nuốt luôn sự kiện cho trình duyệt").containsExactly("QUEUED");
    }

    @Test
    void IE_het_luot_thi_ghi_verdict_IE() {
        fakes.queue.ieRetryAccepted = false;

        var outcome = useCase.record(result(Verdict.IE, 3));

        assertThat(outcome).isEqualTo(RecordJudgeResultUseCase.Outcome.RECORDED);
        assertThat(fakes.submissions.doneOutcome.verdict()).isEqualTo(Verdict.IE);
        assertThat(fakes.calls).containsExactly(
                "queue.retryIe",
                "queue.releaseWithOptimisticLock",
                "judgeRuns.insertIfAbsent",
                "submissions.markDone");
    }

    @Test
    void judge_run_lay_du_lieu_tu_cau_khoa_lac_quan() {
        useCase.record(result(Verdict.AC, 1));

        var run = fakes.judgeRuns.inserted;
        assertThat(run.languageId()).isEqualTo(3);
        assertThat(run.testdataVersion()).isEqualTo(5);
        assertThat(run.hostName()).isEqualTo("mac-m1max-host");
        assertThat(run.finishedAt()).isEqualTo(JudgingFakes.NOW);
        assertThat(run.attempt()).isEqualTo(1);
    }

    @Test
    void CE_van_ghi_duoc_verdict_du_worker_gui_kem_so_do() {
        var ce = new JudgeResultDto(101L, 1, Verdict.CE, 0, 100, null, 0, 999, 999,
                "error: expected ';'", null, "mac-m1max-host", JudgingFakes.hostFactor(),
                JudgingFakes.NOW, List.of());

        useCase.record(ce);

        assertThat(fakes.submissions.doneOutcome.verdict()).isEqualTo(Verdict.CE);
        assertThat(fakes.submissions.doneOutcome.timeMs()).isNull();
        assertThat(fakes.judgeRuns.inserted.compileLog()).contains("expected ';'");
    }
}
