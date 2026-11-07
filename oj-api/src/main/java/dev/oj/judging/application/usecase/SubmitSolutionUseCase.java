package dev.oj.judging.application.usecase;

import dev.oj.judging.application.port.JudgeJobPublisher;
import dev.oj.judging.application.port.JudgeQueueRepository;
import dev.oj.judging.application.port.LanguageRepository;
import dev.oj.judging.application.port.SourceBlobRepository;
import dev.oj.judging.application.port.SubmissionRateLimiter;
import dev.oj.judging.application.port.SubmissionRepository;
import dev.oj.judging.domain.DomainRules;
import dev.oj.judging.domain.JudgingException;
import dev.oj.judging.domain.SourceBlob;
import dev.oj.judging.domain.SubmissionStatus;
import dev.oj.platform.contest.ContestWindowQuery;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.security.RequiresRole;
import dev.oj.platform.settings.SystemSettings;
import dev.oj.problems.application.usecase.GetProblemUseCase;
import dev.oj.problems.domain.Problem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@RequiresRole
@Service
public class SubmitSolutionUseCase {

    private static final Logger log = LoggerFactory.getLogger(SubmitSolutionUseCase.class);

    private final CurrentUserProvider currentUser;
    private final GetProblemUseCase problems;
    private final LanguageRepository languages;
    private final SourceBlobRepository sourceBlobs;
    private final SubmissionRepository submissions;
    private final SubmissionRateLimiter rateLimiter;
    private final ContestWindowQuery lichThi;
    private final SystemSettings congTac;
    private final JudgeQueueRepository queue;
    private final JudgeJobPublisher events;
    private final TransactionTemplate tx;

    public SubmitSolutionUseCase(CurrentUserProvider currentUser,
                                 GetProblemUseCase problems,
                                 LanguageRepository languages,
                                 SourceBlobRepository sourceBlobs,
                                 SubmissionRepository submissions,
                                 SubmissionRateLimiter rateLimiter,
                                 ContestWindowQuery lichThi,
                                 SystemSettings congTac,
                                 JudgeQueueRepository queue,
                                 JudgeJobPublisher events,
                                 @Qualifier("appTransactionManager") PlatformTransactionManager txManager) {
        this.currentUser = currentUser;
        this.problems = problems;
        this.languages = languages;
        this.sourceBlobs = sourceBlobs;
        this.submissions = submissions;
        this.rateLimiter = rateLimiter;
        this.lichThi = lichThi;
        this.congTac = congTac;
        this.queue = queue;
        this.events = events;
        this.tx = new TransactionTemplate(txManager);
    }

    public SubmissionAccepted submit(Command command) {
        CurrentUserProvider.CurrentUser nguoiGoi = currentUser.current();
        long userId = nguoiGoi.id();

        if (!congTac.bat(SystemSettings.NHAN_BAI_NOP, true)) {
            throw JudgingException.dangBaoTri();
        }

        SourceBlob blob = SourceBlob.of(command.source());
        LanguageRepository.Language language = languages.findEnabledByCode(command.languageCode())
                .orElseThrow(() -> JudgingException.languageNotAvailable(command.languageCode()));
        Problem problem = problems.submittableById(command.problemId());

        rateLimiter.kiemTraVaGhiNhan(userId);

        Long contestId = laNguoiRaDe(nguoiGoi, problem) ? null
                : lichThi.contestDangChayChuaDe(problem.id()).stream().boxed()
                        .findFirst().orElse(null);

        long submissionId = persist(userId, contestId, problem, language, blob);
        publishQuietly(submissionId);
        return new SubmissionAccepted(submissionId, SubmissionStatus.QUEUED);
    }

    private static boolean laNguoiRaDe(CurrentUserProvider.CurrentUser nguoiGoi, Problem problem) {
        return nguoiGoi.isAdmin() || nguoiGoi.id() == problem.ownerId();
    }

    private long persist(long userId, Long contestId, Problem problem,
                         LanguageRepository.Language language, SourceBlob blob) {
        return tx.execute(status -> {
            sourceBlobs.saveIfAbsent(blob);
            long id = submissions.insert(new SubmissionRepository.NewSubmission(
                    userId, problem.id(), contestId, language.id(),
                    blob.sha256(), blob.byteSize(), problem.currentTestdataVersion()));
            queue.enqueue(id, DomainRules.PRIORITY_LIVE);
            return id;
        });
    }

    private void publishQuietly(long submissionId) {
        try {
            events.publishEnqueued(submissionId);
        } catch (Exception e) {
            log.warn("publishEnqueued hỏng cho submission {} — bài vẫn trong judge_queue, "
                    + "reaper sẽ nhặt. Người dùng vẫn nhận 202.", submissionId, e);
        }
    }

    public record Command(long problemId, String languageCode, String source) {

        @Override
        public String toString() {
            return "Command[problemId=" + problemId + ", languageCode=" + languageCode
                    + ", sourceBytes=" + (source == null ? 0 : source.length()) + "]";
        }
    }

    public record SubmissionAccepted(long submissionId, SubmissionStatus status) {
    }

}
