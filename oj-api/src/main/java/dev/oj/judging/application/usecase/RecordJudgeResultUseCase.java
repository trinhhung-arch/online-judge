package dev.oj.judging.application.usecase;

import dev.oj.contract.JudgeResultDto;
import dev.oj.judging.application.port.JudgeQueueRepository;
import dev.oj.judging.application.port.JudgeQueueRepository.ReleasedSubmission;
import dev.oj.judging.application.port.JudgeRunRepository;
import dev.oj.judging.application.port.SubmissionEventBus;
import dev.oj.judging.application.port.SubmissionRepository;
import dev.oj.judging.domain.JudgeOutcome;
import dev.oj.judging.domain.JudgeRun;
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.config.AfterCommit;
import dev.oj.platform.config.JudgeTransactional;
import dev.oj.platform.security.InternalAccess;
import dev.oj.platform.trace.TraceIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * ★ Ghi verdict — {@code POST /internal/judge/result}. <b>Chứa khoá lạc quan</b> (bất biến #7).
 *
 * <p>Toàn bộ giá trị của file này nằm ở <b>thứ tự ba câu lệnh</b>, và thứ tự đó không được đổi:
 *
 * <pre>
 *   1. releaseWithOptimisticLock   DELETE ... WHERE attempt=?   -> 0 dòng thì DỪNG, im lặng
 *   2. judgeRuns.insertIfAbsent    PK (submission_id, attempt)  -> lớp chống trùng thứ hai
 *   3. submissions.markDone        HOT update trên bảng nóng
 * </pre>
 *
 * <p>RabbitMQ là at-least-once: một job <b>sẽ</b> có lúc được giao hai lần, và một worker bị
 * reaper thu hồi <b>sẽ</b> có lúc trả kết quả về muộn. Không có câu số 1, có ngày verdict
 * đúng bị ghi đè bởi kết quả của một lần chấm đã chết — và không ai phát hiện ra, vì hệ thống
 * vẫn "chạy bình thường". Đó là R2 ("0 bài bị chấm 2 lần").
 *
 * <p>Trả về {@code 204} cho worker trong <b>cả ba</b> trường hợp dưới đây. Worker không được
 * coi 204 là "đã ghi" — nó chỉ có nghĩa "API đã nhận và tự quyết định" (javadoc của
 * {@link JudgeResultDto}).
 */
@InternalAccess("worker, qua POST /internal/judge/result. Chủ sở hữu bài nộp đọc từ database, không hỏi request.")
@Service
public class RecordJudgeResultUseCase {

    private static final Logger log = LoggerFactory.getLogger(RecordJudgeResultUseCase.class);

    private final JudgeQueueRepository queue;
    private final JudgeRunRepository judgeRuns;
    private final SubmissionRepository submissions;
    private final AppProperties.Judge config;
    private final Clock clock;
    private final SubmissionEventBus events;

    public RecordJudgeResultUseCase(JudgeQueueRepository queue,
                                    JudgeRunRepository judgeRuns,
                                    SubmissionRepository submissions,
                                    AppProperties properties,
                                    Clock clock,
                                    SubmissionEventBus events) {
        this.queue = queue;
        this.judgeRuns = judgeRuns;
        this.submissions = submissions;
        this.config = properties.judge();
        this.clock = clock;
        this.events = events;
    }

    /**
     * @param result kết quả worker gửi về. Mọi trường đã được {@code JudgeResultDto} kiểm và
     *               cắt ở biên HTTP, nên ở đây không còn phép kiểm nào có thể làm hỏng đường ghi
     */
    @JudgeTransactional
    public Outcome record(JudgeResultDto result) {
        long id = result.submissionId();
        int attempt = result.attempt();

        // ---- FR-SUB-12: nhánh IE rẽ TRƯỚC khoá lạc quan ----
        // Lỗi hệ thống không phải lỗi bài nộp. Ghi IE ngay lần đầu là đổ lỗi cho người dùng
        // vì một cái box không dọn được. Câu retryIe cũng mang điều kiện attempt, nên nó an
        // toàn ngang khoá lạc quan — không có cửa sổ nào để một kết quả chết làm bài sống lại.
        if (result.verdict().isSystemFailure()
                && queue.retryIe(id, attempt, config.maxIeRetries())) {
            log.warn("IE cho submission {} attempt {} trên máy {} — đã đưa lại vào hàng đợi",
                    id, attempt, result.hostName());
            return Outcome.IE_RETRY_SCHEDULED;
        }

        // ---- ★ KHOÁ LẠC QUAN: câu lệnh đầu tiên, không có gì được ghi trước nó ----
        Optional<ReleasedSubmission> released = queue.releaseWithOptimisticLock(id, attempt);
        if (released.isEmpty()) {
            // Kết quả trùng, hoặc kết quả của một attempt đã bị reaper thu hồi.
            // Bỏ qua IM LẶNG: không ghi, không ném, không trả lỗi. Đây là cơ chế, không phải lỗi.
            log.debug("Bỏ qua kết quả của submission {} attempt {} — khoá lạc quan từ chối",
                    id, attempt);
            return Outcome.IGNORED;
        }

        writeVerdict(released.get(), result);
        return Outcome.RECORDED;
    }

    /** Hai câu ghi còn lại, cùng transaction với câu khoá lạc quan ở trên. */
    private void writeVerdict(ReleasedSubmission released, JudgeResultDto result) {
        JudgeOutcome outcome = new JudgeOutcome(
                result.verdict(), result.score(), result.maxScore(),
                result.failedTestOrdinal(), result.timeMs(), result.memoryKb());
        Instant now = clock.instant();

        boolean inserted = judgeRuns.insertIfAbsent(new JudgeRun(
                released.submissionId(), released.attempt(), result.hostName(),
                result.hostFactor(), released.languageId(), released.testdataVersion(),
                outcome, result.testsRun(), result.compileLog(), result.isolateStatus(),
                TraceIdFilter.current(), result.startedAt(), now));

        if (!inserted) {
            // Hàng judge_queue vừa bị xoá nhưng judge_runs đã có bản ghi cho attempt này:
            // hai lớp chống trùng nói khác nhau. Ghi ERROR chứ đừng nuốt — nó có nghĩa là ở
            // đâu đó có một đường ghi verdict thứ hai không đi qua khoá lạc quan.
            log.error("judge_runs đã có submission {} attempt {} dù khoá lạc quan vừa thắng",
                    released.submissionId(), released.attempt());
        }
        // FR-PROB-06 — cùng transaction, và SAU insertIfAbsent: khoá ngoại của
        // judge_run_subtasks trỏ vào judge_runs(submission_id, attempt).
        judgeRuns.insertSubtaskResults(
                released.submissionId(), released.attempt(), result.subtasks());
        submissions.markDone(released.submissionId(), released.attempt(), outcome, now);

        // ★ Bước 3.9 — SAU COMMIT, không phải trong transaction. Đẩy tin rồi commit hỏng thì
        // trang hiện "AC" cho một bài mà DB vẫn ghi là đang chấm, và F5 một cái là verdict
        // biến mất. Sự kiện chỉ mang TRẠNG THÁI: không có failedTestOrdinal, không có log
        // compiler — chi tiết đi qua GET /submissions/{id}, nơi có bộ lọc feedback_level
        // (bất biến #1). Xem javadoc SubmissionEventBus.
        AfterCommit.run(() -> events.publish(SubmissionEventBus.SubmissionEvent.done(
                released.submissionId(), released.attempt(), outcome.verdict().name())));
    }

    /** Ba kết cục, và cả ba đều là {@code 204} với worker. */
    public enum Outcome {

        /** Verdict đã vào DB. Hàng trong {@code judge_queue} đã biến mất. */
        RECORDED,

        /**
         * Khoá lạc quan từ chối — kết quả trùng hoặc thuộc attempt đã chết.
         * <b>Không có gì được ghi, và đó là hành vi đúng.</b>
         */
        IGNORED,

        /** FR-SUB-12: {@code IE} còn lượt, bài đã quay lại hàng đợi, chưa ghi verdict nào. */
        IE_RETRY_SCHEDULED
    }
}
