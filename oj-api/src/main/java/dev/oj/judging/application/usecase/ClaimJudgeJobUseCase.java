package dev.oj.judging.application.usecase;

import dev.oj.contract.ClaimRequestDto;
import dev.oj.contract.JudgeJobDto;
import dev.oj.judging.application.port.JudgeQueueRepository;
import dev.oj.judging.application.port.JudgeQueueRepository.ClaimedJob;
import dev.oj.judging.application.port.SubmissionRepository;
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.config.JudgeTransactional;
import dev.oj.platform.security.InternalAccess;
import dev.oj.platform.trace.TraceIdFilter;
import dev.oj.problems.application.port.JudgeSpecRepository;
import dev.oj.problems.domain.JudgeSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

@InternalAccess("worker, qua POST /internal/judge/claim — InternalSecretFilter đứng trước. Worker không phải một người dùng và không mang JWT.")
@Service
public class ClaimJudgeJobUseCase {

    private static final Logger log = LoggerFactory.getLogger(ClaimJudgeJobUseCase.class);

    private final JudgeQueueRepository queue;
    private final SubmissionRepository submissions;
    private final JudgeSpecRepository judgeSpecs;
    private final AppProperties.Judge config;

    public ClaimJudgeJobUseCase(JudgeQueueRepository queue,
                                SubmissionRepository submissions,
                                JudgeSpecRepository judgeSpecs,
                                AppProperties properties) {
        this.queue = queue;
        this.submissions = submissions;
        this.judgeSpecs = judgeSpecs;
        this.config = properties.judge();
    }

    @JudgeTransactional
    public Optional<JudgeJobDto> claim(ClaimRequestDto request) {
        Optional<ClaimedJob> claimed = queue.claim(request.hostName(), config.leaseSeconds());
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        ClaimedJob job = claimed.get();

        submissions.markJudging(job.submissionId(), job.attempt());

        Optional<JudgeSpec> spec = judgeSpecs.findJudgeSpec(job.problemId(), job.testdataVersion());
        if (spec.isEmpty()) {
            return skipBrokenJob(job);
        }
        return Optional.of(buildJob(job, spec.get()));
    }

    private Optional<JudgeJobDto> skipBrokenJob(ClaimedJob job) {
        log.error("Không dựng được job cho submission {}: đề {} không có testdata version {}. "
                        + "Bài giữ lease tới khi reaper thu hồi. SETTER cần kiểm tra lại đề này.",
                job.submissionId(), job.problemId(), job.testdataVersion());
        return Optional.empty();
    }

    static final String SOURCE_BASE_NAME = "Main";

    private static String sourceFileName(ClaimedJob.LanguageSpec language) {
        return SOURCE_BASE_NAME + '.' + language.sourceExtension();
    }

    private JudgeJobDto buildJob(ClaimedJob job, JudgeSpec spec) {
        ClaimedJob.LanguageSpec language = job.language();
        return JudgeJobDto.builder()
                .submission(job.submissionId(), job.attempt())
                .traceId(TraceIdFilter.current())
                .language(language.code(), language.compileCommand(), language.runCommand())
                .compileLimits(language.compileTimeLimitMs(), language.compileMemoryKb())
                .runLimitsOnReferenceHost(
                        spec.timeLimitOnReferenceHost(
                                language.timeMultiplier(), language.startupOverheadMs()),
                        spec.memoryLimitOnReferenceHost(language.memoryOverheadKb()),
                        spec.outputLimitKb())
                .source(sourceFileName(language), job.sourceContent(), job.sourceSha256())
                .checker(spec.checkerType(), spec.checkerEpsilon())
                .scoring(spec.scoringMode(), spec.maxScore())
                .testdata(spec.testdataVersion(), spec.manifestSha256(), spec.testcases())
                .subtasks(spec.subtasks())
                .build();
    }
}
