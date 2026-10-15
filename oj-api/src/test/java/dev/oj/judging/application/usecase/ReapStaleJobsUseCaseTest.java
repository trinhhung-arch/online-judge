package dev.oj.judging.application.usecase;

import dev.oj.contract.Verdict;
import dev.oj.judging.application.port.JudgeQueueRepository.ExhaustedJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reaper — mạng an toàn cho năm loại sự cố, và là task có tỉ lệ giá trị/công sức cao nhất
 * toàn dự án ({@code nfrplan.md} 5.1). Từ V16 nó có trần: hết lượt thả lại thì bỏ cuộc.
 */
class ReapStaleJobsUseCaseTest {

    private JudgingFakes fakes;
    private JudgingFakes.FakeEventBus events;
    private ReapStaleJobsUseCase useCase;

    @BeforeEach
    void setUp() {
        fakes = new JudgingFakes();
        events = new JudgingFakes.FakeEventBus();
        RecordJudgeResultUseCase verdicts = new RecordJudgeResultUseCase(fakes.queue,
                fakes.judgeRuns, fakes.submissions, JudgingFakes.properties(), JudgingFakes.CLOCK,
                events, fakes.publisher);
        useCase = new ReapStaleJobsUseCase(fakes.queue, fakes.submissions, verdicts,
                JudgingFakes.properties(), fakes.txManager);
    }

    /** ★ Lease hết hạn → về QUEUED. Và {@code attempt} <b>không</b> tăng ở đây. */
    @Test
    void lease_het_han_thi_ve_QUEUED_va_attempt_khong_tang() {
        fakes.queue.expired = List.of(101L, 102L);

        int reaped = useCase.reap();

        assertThat(reaped).isEqualTo(2);
        assertThat(fakes.submissions.requeued).containsExactly(101L, 102L);
        // Không có lời gọi nào đụng tới attempt — lần claim kế tiếp mới tăng.
        assertThat(fakes.submissions.judgingAttempt).isNull();
        // Thả lease và đưa về QUEUED nằm trong MỘT transaction.
        assertThat(fakes.calls).containsExactly("tx.begin", "queue.reapExpired",
                "submissions.markQueued", "tx.commit", "queue.exhaustedJobs");
    }

    /** Còn lượt thì reaper chỉ thả lại — không đụng tới verdict nào. */
    @Test
    void con_luot_thi_khong_ghi_verdict() {
        fakes.queue.expired = List.of(101L);

        useCase.reap();

        assertThat(fakes.calls).noneMatch(c -> c.contains("markDone") || c.contains("judgeRuns")
                || c.contains("releaseWithOptimisticLock"));
    }

    /** Chu kỳ 15 giây chạy suốt ngày — không có gì quá hạn thì không tốn một câu ghi nào. */
    @Test
    void khong_co_gi_qua_han_thi_khong_ghi_gi() {
        int reaped = useCase.reap();

        assertThat(reaped).isZero();
        assertThat(fakes.calls).containsExactly("tx.begin", "queue.reapExpired", "tx.commit",
                "queue.exhaustedJobs");
    }

    @Test
    @DisplayName("trần đọc từ oj.judge.max-reaps, không viết cứng")
    void tran_doc_tu_cau_hinh() {
        useCase.reap();

        assertThat(fakes.queue.maxReapsSeen)
                .isEqualTo(JudgingFakes.properties().judge().maxReaps());
    }

    /**
     * ★ V16 — hết lượt thì bỏ cuộc bằng IE, qua <b>đúng</b> ba câu lệnh của mọi verdict: khoá
     * lạc quan trước, rồi {@code judge_runs}, rồi bảng nóng. Và không rẽ nhánh FR-SUB-12 — thả
     * lại vì IE chính là vòng quay vừa bị chặn.
     */
    @Test
    @DisplayName("★ hết lượt thả lại → ghi IE qua khoá lạc quan, không thả lại nữa")
    void het_luot_thi_ghi_IE_qua_khoa_lac_quan() {
        fakes.queue.exhausted = List.of(new ExhaustedJob(101L, 3, JudgingFakes.NOW));

        int reaped = useCase.reap();

        assertThat(reaped).isEqualTo(1);
        assertThat(fakes.calls).containsSubsequence("queue.exhaustedJobs",
                "queue.releaseWithOptimisticLock", "judgeRuns.insertIfAbsent",
                "submissions.markDone");
        assertThat(fakes.calls).doesNotContain("queue.retryIe", "submissions.markQueued");
        assertThat(fakes.submissions.doneOutcome.verdict()).isEqualTo(Verdict.IE);
        assertThat(fakes.judgeRuns.inserted.hostName())
                .isEqualTo(RecordJudgeResultUseCase.MAY_BO_CUOC);
        assertThat(fakes.judgeRuns.inserted.startedAt())
                .as("started_at là lúc attempt hết lượt được giao")
                .isEqualTo(JudgingFakes.NOW);
        assertThat(events.published).singleElement()
                .satisfies(e -> assertThat(e.verdict()).isEqualTo("IE"));
    }

    /**
     * Worker trả kết quả thật kịp trước lượt bỏ cuộc: hàng đã biến mất, khoá lạc quan từ chối,
     * và lượt bỏ cuộc im lặng — kết quả thật đứng yên, không bị IE ghi đè.
     */
    @Test
    @DisplayName("★ kết quả thật tới trước thì lượt bỏ cuộc im lặng, không ghi đè")
    void ket_qua_that_toi_truoc_thi_bo_cuoc_im_lang() {
        fakes.queue.exhausted = List.of(new ExhaustedJob(101L, 3, JudgingFakes.NOW));
        fakes.queue.lockWins = false;

        int reaped = useCase.reap();

        assertThat(reaped).isZero();
        assertThat(fakes.calls).noneMatch(c -> c.contains("markDone") || c.contains("judgeRuns"));
        assertThat(events.published).isEmpty();
    }
}
