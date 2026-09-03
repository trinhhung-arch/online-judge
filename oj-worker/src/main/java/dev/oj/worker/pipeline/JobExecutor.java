package dev.oj.worker.pipeline;

import dev.oj.contract.JudgeJobDto;
import dev.oj.contract.JudgeResultDto;
import dev.oj.contract.ScoringMode;
import dev.oj.contract.TestcaseMetaDto;
import dev.oj.contract.Verdict;
import dev.oj.worker.calibration.HostBenchmark;
import dev.oj.worker.compile.Compiler;
import dev.oj.worker.config.WorkerProperties;
import dev.oj.worker.report.BatchReporter;
import dev.oj.worker.run.SubtaskScorer;
import dev.oj.worker.run.TestRunner;
import dev.oj.worker.run.checker.Checker;
import dev.oj.worker.run.checker.Checkers;
import dev.oj.worker.sandbox.IsolateBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Vòng đời một job: mượn slot → mở box → biên dịch → chạy từng test → <b>dọn box trong
 * {@code finally}</b>. Bước 2.6 của {@code build-order.md}.
 *
 * <h2>Ba thứ được cấu trúc bảo đảm, không phải kỷ luật bảo đảm</h2>
 * <ol>
 *   <li>Box luôn được dọn — {@code try (var box = ...)}, không phải một dòng
 *       {@code finally} mà người sửa sau có thể vô tình nhảy qua bằng một {@code return}.</li>
 *   <li>Slot luôn được trả — {@code finally} bọc ngoài cùng, kể cả khi {@code IsolateBox.open}
 *       ném ngay ở dòng đầu.</li>
 *   <li>Không có đường nào ra khỏi đây mà không trả về một {@link JudgeResultDto} hoặc ném —
 *       và {@code IsolateJudgeRunner} biến mọi cú ném thành {@code IE}.</li>
 * </ol>
 *
 * <h2>Early exit: dừng ở test sai đầu tiên</h2>
 * Cắt khoảng một nửa thời gian chấm trung bình ({@code oj-worker/CLAUDE.md} mục 4), vì phần
 * lớn bài sai thì sai sớm. <b>Trừ khi</b> đề chấm theo {@code SUBTASK}: lúc đó test 3 sai
 * không nói gì về nhóm 2, và dừng sớm là chấm sai điểm.
 */
@Component
public class JobExecutor {

    private static final Logger log = LoggerFactory.getLogger(JobExecutor.class);

    private final WorkerProperties properties;
    private final SlotPool slots;
    private final Compiler compiler;
    private final TestRunner testRunner;
    private final HostBenchmark hostBenchmark;
    private final BatchReporter batchReporter;

    public JobExecutor(WorkerProperties properties, SlotPool slots, Compiler compiler,
                       TestRunner testRunner, HostBenchmark hostBenchmark,
                       BatchReporter batchReporter) {
        this.properties = properties;
        this.slots = slots;
        this.compiler = compiler;
        this.testRunner = testRunner;
        this.hostBenchmark = hostBenchmark;
        this.batchReporter = batchReporter;
    }

    public JudgeResultDto execute(JudgeJobDto job, String sourceFileName, Instant startedAt)
            throws InterruptedException {
        int boxId = slots.acquire(properties.lease().toMillis());
        try (IsolateBox box = IsolateBox.open(
                properties.sandbox(), boxId, properties.sandbox().cache().dir().resolve("meta"))) {

            Compiler.CompileResult compiled = compiler.compile(box, job, sourceFileName);
            if (compiled.failed()) {
                return JudgeResultDto.compileError(job.submissionId(), job.attempt(),
                        job.maxScore(), properties.hostName(), hostBenchmark.current(),
                        startedAt, compiled.compileLog());
            }
            return judgeAllTests(job, box, compiled, startedAt);

        } finally {
            slots.release(boxId);
        }
    }

    private JudgeResultDto judgeAllTests(JudgeJobDto job, IsolateBox box,
                                         Compiler.CompileResult compiled, Instant startedAt) {
        // ★ host_factor lấy từ phép đo của CHÍNH máy này, không lấy từ hằng số trong config:
        // API gửi giới hạn đã quy về máy chấm chuẩn, worker nhân tiếp hệ số của mình
        // (JudgeJobDto javadoc ghi chú 1). Nhân ở phía API nữa là nhân hai lần.
        java.math.BigDecimal hostFactor = hostBenchmark.current();
        Checker checker = Checkers.of(job.checkerType(), job.checkerEpsilon());

        // ★ Bước 3.7 — try-with-resources vì lô cuối gần như không bao giờ đủ 20, và với
        // early exit thì một lô dở dang là chuyện thường chứ không phải ngoại lệ: bài sai ở
        // test 3 chỉ có ba phần tử trong lô đầu.
        try (BatchReporter.Session progress = batchReporter.open(job)) {
            SubtaskScorer.TestExecutor executor = testcase -> {
                TestRunner.TestOutcome outcome = testRunner.run(
                        box, job, compiled.artifact(), testcase, hostFactor, checker);
                progress.add(testcase.ordinal(), outcome.verdict(),
                        outcome.cpuTimeMs(), outcome.memoryKb());
                return outcome;
            };

            // ★ Bước 3.3 — HAI đường chấm, không phải một vòng lặp có `if` bên trong.
            //
            // Chúng khác nhau ở chỗ căn bản chứ không ở chi tiết: chấm cả bài thì DỪNG ở test
            // sai đầu tiên, còn chấm theo nhóm thì test 3 sai không nói gì về nhóm 2 — dừng
            // sớm là chấm sai điểm. Nhồi cả hai vào một vòng lặp là mời một ngày nào đó có
            // người "dọn dẹp" cái `if` và làm hỏng đúng thứ mà FR-PROB-06 sinh ra để đúng.
            SubtaskScorer.Result scored = job.scoringMode() == ScoringMode.SUBTASK
                    ? SubtaskScorer.score(job, executor)
                    : scoreAllOrNothing(job, executor);

            log.debug("submission {} attempt {}: {} — {}/{} điểm sau {}/{} test",
                    job.submissionId(), job.attempt(), scored.verdict(), scored.score(),
                    job.maxScore(), scored.testsRun(), job.testcases().size());

            return new JudgeResultDto(job.submissionId(), job.attempt(), scored.verdict(),
                    scored.score(), job.maxScore(), scored.failedOrdinal(), scored.testsRun(),
                    (int) scored.maxCpuTimeMs(), (int) scored.maxMemoryKb(),
                    compiled.compileLog(), null,
                    properties.hostName(), hostFactor, startedAt, scored.subtasks());
        }
    }

    /**
     * Chấm cả bài: đúng hết thì trọn điểm, sai một test thì 0 — và <b>dừng ngay</b> ở test
     * sai đầu tiên.
     *
     * <p>Early exit cắt khoảng một nửa thời gian chấm trung bình
     * ({@code oj-worker/CLAUDE.md} mục 4), vì phần lớn bài sai thì sai sớm. Nó đúng ở đây
     * chính vì điểm chỉ có hai giá trị: khi đã biết không phải trọn điểm thì mọi test còn
     * lại không đổi được kết quả nữa.
     */
    private static SubtaskScorer.Result scoreAllOrNothing(JudgeJobDto job,
                                                          SubtaskScorer.TestExecutor executor) {
        Verdict verdict = Verdict.AC;
        Integer failedOrdinal = null;
        long maxCpuMs = 0;
        long maxMemoryKb = 0;
        int testsRun = 0;

        for (TestcaseMetaDto testcase : job.testcases()) {
            TestRunner.TestOutcome outcome = executor.run(testcase);
            testsRun++;
            maxCpuMs = Math.max(maxCpuMs, outcome.cpuTimeMs());
            maxMemoryKb = Math.max(maxMemoryKb, outcome.memoryKb());
            if (!outcome.accepted()) {
                // Verdict của cả bài là verdict của test SAI ĐẦU TIÊN, không phải test sai
                // cuối cùng: đó là test mà thí sinh cần xem tới trước.
                verdict = outcome.verdict();
                failedOrdinal = testcase.ordinal();
                break;
            }
        }

        int score = verdict == Verdict.AC ? job.maxScore() : 0;
        return new SubtaskScorer.Result(score, verdict, failedOrdinal, testsRun,
                maxCpuMs, maxMemoryKb, java.util.List.of());
    }

}
