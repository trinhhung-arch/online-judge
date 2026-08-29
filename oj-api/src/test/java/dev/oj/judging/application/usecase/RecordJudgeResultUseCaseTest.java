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

    private JudgingFakes.FakeEventBus events;

    @BeforeEach
    void setUp() {
        fakes = new JudgingFakes();
        events = new JudgingFakes.FakeEventBus();
        useCase = new RecordJudgeResultUseCase(fakes.queue, fakes.judgeRuns, fakes.submissions,
                JudgingFakes.properties(), JudgingFakes.CLOCK, events);
    }

    /**
     * ★ Bước 3.9 — verdict phải đi ra luồng SSE, nếu không thì trang bài nộp đứng im cho tới
     * khi người dùng tự F5, và FR-SUB-05 chỉ tồn tại trên giấy.
     */
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

    /**
     * ★ Bất biến #1 ở tầng sự kiện. Sự kiện SSE đi thẳng ra trình duyệt mà KHÔNG qua bộ lọc
     * {@code feedback_level} — nên nó không được phép mang bất cứ thứ gì bộ lọc ấy có thể
     * cấm. Bài này sai ở test 7; con số đó phải ở lại phía sau.
     */
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

        // Hai phép ĐẾM được cho phép tường minh. Chúng nói "đã chạy 40/100", không nói test
        // nào sai — và một con số đếm thì không có chỗ nào để giấu một chi tiết.
        // Mọi trường MỚI chứa những chuỗi trên đều phải được thêm vào đây một cách có ý thức,
        // và lúc thêm thì người sửa buộc phải đọc javadoc của SubmissionEventBus.
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
        // testsDone/totalTests là hai phép ĐẾM, không phải danh sách — chúng được phép, và
        // vòng lặp trên cố ý không cấm chữ "tests" ở dạng số nhiều đếm được.
        org.assertj.core.api.Assertions.assertThat(fields)
                .contains("submissionid", "status", "verdict");
    }

    /** Kết quả bị khoá lạc quan từ chối thì không có gì xảy ra — kể cả một sự kiện. */
    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("kết quả trùng bị từ chối thì KHÔNG đẩy sự kiện nào")
    void ket_qua_trung_khong_day_su_kien() {
        useCase.record(result(Verdict.AC, 1));
        fakes.queue.lockWins = false;                    // hàng judge_queue đã bị xoá ở lần một
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
        assertThat(run.hostName()).isEqualTo("mac-m1max-host");
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
                "error: expected ';'", null, "mac-m1max-host", JudgingFakes.hostFactor(),
                JudgingFakes.NOW, List.of());

        useCase.record(ce);

        assertThat(fakes.submissions.doneOutcome.verdict()).isEqualTo(Verdict.CE);
        assertThat(fakes.submissions.doneOutcome.timeMs()).isNull();
        assertThat(fakes.judgeRuns.inserted.compileLog()).contains("expected ';'");
    }
}
