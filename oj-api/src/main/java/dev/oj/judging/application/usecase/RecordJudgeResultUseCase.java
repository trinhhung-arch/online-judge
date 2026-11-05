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
import dev.oj.judging.application.port.JudgeJobPublisher;
import dev.oj.platform.config.JudgeTransactional;
import dev.oj.platform.security.InternalAccess;
import dev.oj.platform.trace.TraceIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

@InternalAccess("worker, qua POST /internal/judge/result; và reaper (lịch trong tiến trình) qua boCuoc. Chủ sở hữu bài nộp đọc từ database, không hỏi request.")
@Service
public class RecordJudgeResultUseCase {

    private static final Logger log = LoggerFactory.getLogger(RecordJudgeResultUseCase.class);

    static final String MAY_BO_CUOC = "reaper";

    private final JudgeQueueRepository queue;
    private final JudgeRunRepository judgeRuns;
    private final SubmissionRepository submissions;
    private final AppProperties.Judge config;
    private final Clock clock;
    private final JudgeJobPublisher chuong;
    private final SubmissionEventBus events;

    public RecordJudgeResultUseCase(JudgeQueueRepository queue,
                                    JudgeRunRepository judgeRuns,
                                    SubmissionRepository submissions,
                                    AppProperties properties,
                                    Clock clock,
                                    SubmissionEventBus events,
                                    JudgeJobPublisher chuong) {
        this.queue = queue;
        this.judgeRuns = judgeRuns;
        this.submissions = submissions;
        this.config = properties.judge();
        this.clock = clock;
        this.chuong = chuong;
        this.events = events;
    }

    @JudgeTransactional
    public Outcome record(JudgeResultDto result) {
        long id = result.submissionId();
        int attempt = result.attempt();

        if (result.verdict().isSystemFailure()
                && queue.retryIe(id, attempt, config.maxIeRetries())) {
            log.warn("IE cho submission {} attempt {} trên máy {} — đã đưa lại vào hàng đợi",
                    id, attempt, result.hostName());
            AfterCommit.run(() -> goCuaImLang(id));
            return Outcome.IE_RETRY_SCHEDULED;
        }

        Optional<ReleasedSubmission> released = queue.releaseWithOptimisticLock(id, attempt);
        if (released.isEmpty()) {
            log.debug("Bỏ qua kết quả của submission {} attempt {} — khoá lạc quan từ chối",
                    id, attempt);
            return Outcome.IGNORED;
        }

        writeVerdict(released.get(), result);
        return Outcome.RECORDED;
    }

    @JudgeTransactional
    public Outcome boCuoc(long submissionId, int attempt, int soLanHetLease, Instant claimedAt) {
        Optional<ReleasedSubmission> released = queue.releaseWithOptimisticLock(submissionId, attempt);
        if (released.isEmpty()) {
            return Outcome.IGNORED;
        }
        writeVerdict(released.get(), JudgeResultDto.internalError(submissionId, attempt,
                MAY_BO_CUOC, java.math.BigDecimal.ONE, claimedAt,
                "REAPER: het lease " + soLanHetLease + " lan, khong lan nao co ket qua"));
        log.error("Bỏ cuộc submission {} attempt {} sau {} lần hết lease — đã ghi IE. Bài làm "
                + "sập worker hoặc đề hỏng: cần người xem.", submissionId, attempt, soLanHetLease);
        return Outcome.RECORDED;
    }

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
            log.error("judge_runs đã có submission {} attempt {} dù khoá lạc quan vừa thắng",
                    released.submissionId(), released.attempt());
        }
        judgeRuns.insertSubtaskResults(
                released.submissionId(), released.attempt(), result.subtasks());
        submissions.markDone(released.submissionId(), released.attempt(), outcome, now);

        AfterCommit.run(() -> events.publish(SubmissionEventBus.SubmissionEvent.done(
                released.submissionId(), released.attempt(), outcome.verdict().name())));
    }

    public enum Outcome {

        RECORDED,

        IGNORED,

        IE_RETRY_SCHEDULED
    }
    private void goCuaImLang(long submissionId) {
        try {
            chuong.publishEnqueued(submissionId);
        } catch (RuntimeException e) {
            log.warn("Không gõ cửa được cho submission {} sau IE — bài vẫn trong judge_queue "
                    + "và worker sẽ thấy nó ở nhịp poll kế tiếp: {}", submissionId, e.toString());
        }
    }

}
