package dev.oj.judging.application.usecase;

import dev.oj.contract.JudgeResultDto;
import dev.oj.contract.Verdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ Khoá lạc quan — bất biến #7, chỉ số R2 ("0 bài bị chấm 2 lần").
 *
 * <p>RabbitMQ là at-least-once và reaper có thật, nên hai tình huống dưới đây <b>sẽ</b> xảy
 * ra trong đời hệ thống, không phải "có thể": một kết quả về muộn sau khi bài đã bị thu hồi,
 * và một kết quả được giao hai lần.
 */
class RecordJudgeResultUseCaseTest {

    private JudgingFakes fakes;
    private RecordJudgeResultUseCase useCase;

    @BeforeEach
    void setUp() {
        fakes = new JudgingFakes();
        useCase = new RecordJudgeResultUseCase(fakes.queue, fakes.judgeRuns, fakes.submissions,
                JudgingFakes.properties(), JudgingFakes.CLOCK);
    }

    private JudgeResultDto result(Verdict verdict, int attempt) {
        return new JudgeResultDto(101L, attempt, verdict, verdict.isAccepted() ? 100 : 0, 100,
                verdict.isAccepted() ? null : 7, 20, 230, 4096, null, null,
                "mac-host", JudgingFakes.hostFactor(), JudgingFakes.NOW, List.of());
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

    /**
     * ★ Lock trả 0 dòng → <b>không ghi gì</b>. Đây là kết quả của một attempt đã bị reaper
     * thu hồi, hoặc bản giao trùng của RabbitMQ. Bỏ qua im lặng là hành vi đúng, không phải
     * thiếu sót — và worker vẫn nhận 204.
     */
    @Test
    void lock_tu_choi_thi_khong_mot_cau_ghi_nao_chay() {
        fakes.queue.lockWins = false;

        var outcome = useCase.record(result(Verdict.AC, 1));

        assertThat(outcome).isEqualTo(RecordJudgeResultUseCase.Outcome.IGNORED);
        assertThat(fakes.calls).containsExactly("queue.releaseWithOptimisticLock");
        assertThat(fakes.judgeRuns.inserted).isNull();
        assertThat(fakes.submissions.doneOutcome).isNull();
    }

    /** ★ Gọi hai lần: lần hai bị khoá lạc quan chặn, verdict lần một không đổi. */
    @Test
    void goi_hai_lan_thi_verdict_khong_doi() {
        useCase.record(result(Verdict.WA, 1));
        var first = fakes.submissions.doneOutcome;

        fakes.queue.lockWins = false;                    // hàng judge_queue đã bị xoá ở lần một
        var second = useCase.record(result(Verdict.AC, 1));

        assertThat(second).isEqualTo(RecordJudgeResultUseCase.Outcome.IGNORED);
        assertThat(fakes.submissions.doneOutcome).isSameAs(first);
        assertThat(fakes.submissions.doneOutcome.verdict()).isEqualTo(Verdict.WA);
    }

    /**
     * FR-SUB-12 — {@code IE} còn lượt thì bài quay lại hàng đợi và <b>không có verdict nào
     * được ghi</b>. Nhánh này phải rẽ TRƯỚC khoá lạc quan: rẽ sau thì hàng đã bị xoá và bài
     * không còn đường quay lại.
     */
    @Test
    void IE_con_luot_thi_khong_ghi_verdict_va_khong_dung_toi_khoa_lac_quan() {
        fakes.queue.ieRetryAccepted = true;

        var outcome = useCase.record(result(Verdict.IE, 1));

        assertThat(outcome).isEqualTo(RecordJudgeResultUseCase.Outcome.IE_RETRY_SCHEDULED);
        assertThat(fakes.calls).containsExactly("queue.retryIe");
    }

    /** Hết lượt retry thì {@code IE} thành verdict thật để người dùng biết chuyện gì xảy ra. */
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

    /**
     * {@code judge_runs} lấy {@code language_id} và {@code testdata_version} từ chính câu khoá
     * lạc quan — không có câu {@code SELECT} nào thêm trên transaction ngắn nhất của hệ thống.
     */
    @Test
    void judge_run_lay_du_lieu_tu_cau_khoa_lac_quan() {
        useCase.record(result(Verdict.AC, 1));

        var run = fakes.judgeRuns.inserted;
        assertThat(run.languageId()).isEqualTo(3);
        assertThat(run.testdataVersion()).isEqualTo(5);
        assertThat(run.hostName()).isEqualTo("mac-host");
        assertThat(run.finishedAt()).isEqualTo(JudgingFakes.NOW);
        assertThat(run.attempt()).isEqualTo(1);
    }

    /**
     * CE thì chưa test nào chạy — {@code JudgeOutcome} bỏ hai con số đo thay vì từ chối kết
     * quả. Từ chối là rollback, rollback là bài quay lại hàng đợi và sinh ra đúng kết quả đó
     * lần nữa.
     */
    @Test
    void CE_van_ghi_duoc_verdict_du_worker_gui_kem_so_do() {
        var ce = new JudgeResultDto(101L, 1, Verdict.CE, 0, 100, null, 0, 999, 999,
                "error: expected ';'", null, "mac-host", JudgingFakes.hostFactor(),
                JudgingFakes.NOW, List.of());

        useCase.record(ce);

        assertThat(fakes.submissions.doneOutcome.verdict()).isEqualTo(Verdict.CE);
        assertThat(fakes.submissions.doneOutcome.timeMs()).isNull();
        assertThat(fakes.judgeRuns.inserted.compileLog()).contains("expected ';'");
    }
}
