package dev.oj.worker.pipeline;

import dev.oj.contract.JudgeJobDto;
import dev.oj.contract.JudgeResultDto;
import dev.oj.contract.ScoringMode;
import dev.oj.contract.TestcaseMetaDto;
import dev.oj.contract.Verdict;
import dev.oj.worker.calibration.HostBenchmark;
import dev.oj.worker.compile.Compiler;
import dev.oj.worker.config.WorkerProperties;
import dev.oj.worker.run.TestRunner;
import dev.oj.worker.run.checker.Checker;
import dev.oj.worker.run.checker.Checkers;
import dev.oj.worker.sandbox.IsolateBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

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

    public JobExecutor(WorkerProperties properties, SlotPool slots, Compiler compiler,
                       TestRunner testRunner, HostBenchmark hostBenchmark) {
        this.properties = properties;
        this.slots = slots;
        this.compiler = compiler;
        this.testRunner = testRunner;
        this.hostBenchmark = hostBenchmark;
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
        boolean earlyExitAllowed = job.scoringMode() == ScoringMode.ALL_OR_NOTHING;

        Verdict verdict = Verdict.AC;
        Integer failedOrdinal = null;
        String diagnostic = null;
        long maxCpuMs = 0;
        long maxMemoryKb = 0;
        int testsRun = 0;
        int accepted = 0;

        for (TestcaseMetaDto testcase : job.testcases()) {
            TestRunner.TestOutcome outcome = testRunner.run(
                    box, job, compiled.artifact(), testcase, hostFactor, checker);
            testsRun++;
            maxCpuMs = Math.max(maxCpuMs, outcome.cpuTimeMs());
            maxMemoryKb = Math.max(maxMemoryKb, outcome.memoryKb());

            if (outcome.accepted()) {
                accepted++;
                continue;
            }
            if (failedOrdinal == null) {
                // Verdict của cả bài là verdict của test SAI ĐẦU TIÊN, không phải test sai
                // cuối cùng: đó là test mà thí sinh cần xem tới trước.
                verdict = outcome.verdict();
                failedOrdinal = testcase.ordinal();
                diagnostic = outcome.diagnostic();
            }
            if (earlyExitAllowed) {
                break;
            }
        }

        int score = scoreOf(job, verdict, accepted);
        log.debug("submission {} attempt {}: {} sau {}/{} test",
                job.submissionId(), job.attempt(), verdict, testsRun, job.testcases().size());

        return new JudgeResultDto(job.submissionId(), job.attempt(), verdict, score,
                job.maxScore(), failedOrdinal, testsRun,
                (int) maxCpuMs, (int) maxMemoryKb,
                compiled.compileLog(), diagnostic,
                properties.hostName(), hostFactor, startedAt, List.of());
    }

    /**
     * {@code ALL_OR_NOTHING}: đúng hết thì trọn điểm, sai một test thì 0.
     *
     * <p>{@code SUBTASK} tính điểm theo nhóm và cần {@code SubtaskResultDto} — <b>đó là
     * M3</b>. Ở đây nó cố ý dùng chung công thức {@code ALL_OR_NOTHING} thay vì đoán một cách
     * chia điểm: chia sai điểm trong contest thì bảng xếp hạng sai, và không ai chứng minh
     * được. {@code JudgeSpec} phía API hiện chỉ phát ra {@code ALL_OR_NOTHING}.
     */
    private static int scoreOf(JudgeJobDto job, Verdict verdict, int accepted) {
        return verdict == Verdict.AC && accepted == job.testcases().size() ? job.maxScore() : 0;
    }
}
