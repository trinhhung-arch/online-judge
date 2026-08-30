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
import dev.oj.problems.application.usecase.GetProblemUseCase;
import dev.oj.problems.domain.Problem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ★ <b>NGÂN SÁCH 300ms — P2.</b> Đây là đoạn code quan trọng nhất hệ thống, và nó không được
 * phép hỏng khi mọi thứ khác hỏng.
 *
 * <p>FR-SUB-02: trả {@code submissionId} + {@code QUEUED} trong ≤300ms. <b>Verdict đến sau.</b>
 *
 * <h2>Danh sách những thứ KHÔNG được xuất hiện trong file này</h2>
 * {@code .get()} · {@code .join()} · {@code Thread.sleep} · một lời gọi tới worker ·
 * {@code COUNT(*)} · render Markdown · MinIO · LLM. Review thấy bất kỳ thứ nào — dừng PR
 * ({@code docs/build-order.md} Bước M1-6).
 *
 * <p>Lý do không phải là sự cầu toàn. Chữ "chờ" ở đây phá năm chỉ số cùng lúc: worker chậm
 * là cả site đơ · không tắt được worker để bảo trì · 500 người nộp cùng lúc là 500 connection
 * treo · P2 không thể đạt · và R1 sụp vì bài chỉ tồn tại trong bộ nhớ của một request đang
 * chờ ({@code frplan.md} Phần 0). {@code accept != process} là quyết định quan trọng nhất
 * của M1.
 *
 * <h2>Vì sao ranh giới transaction viết bằng tay chứ không phải {@code @Transactional}</h2>
 * Vì có đúng một dòng phải nằm <b>ngoài</b> transaction, và một annotation trên phương thức
 * thì không diễn đạt được điều đó. Với {@link TransactionTemplate}, chữ COMMIT là một dấu
 * ngoặc nhìn thấy được, và lời gọi publish nằm sau nó — không phải trong một comment mà ai
 * đó sẽ vô tình bỏ qua khi thêm việc.
 */
@RequiresRole  // FR-SUB-02: phải đăng nhập mới nộp được, bài nộp gắn với một người
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
        this.queue = queue;
        this.events = events;
        // Pool app (20), KHÔNG phải pool judge (6): nộp bài là request của người dùng.
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * @return id và trạng thái {@code QUEUED} — controller trả {@code 202 Accepted}
     * @throws JudgingException {@code INVALID} nếu source rỗng/quá 64KB hoặc ngôn ngữ đã tắt;
     *         {@code RATE_LIMITED} nếu nộp lại trong vòng 10 giây (FR-SUB-08)
     * @throws dev.oj.problems.domain.ProblemNotFoundException nếu đề không tồn tại, chưa xuất
     *         bản, hoặc chưa có testdata
     */
    public SubmissionAccepted submit(Command command) {
        long userId = currentUser.current().id();

        // ---- validate: hỏng ở đây thì chưa có gì được ghi, và người dùng nhận 400 rõ ràng --
        SourceBlob blob = SourceBlob.of(command.source());          // 64KB · rỗng (FR-SUB-01)
        LanguageRepository.Language language = languages.findEnabledByCode(command.languageCode())
                .orElseThrow(() -> JudgingException.languageNotAvailable(command.languageCode()));
        Problem problem = problems.submittableById(command.problemId());

        // ---- FR-SUB-08, ĐẶT SAU validate và TRƯỚC persist -----------------------------------
        //
        // Sau validate: một request bị từ chối 400 không phải là "một bài nộp", nên nó không
        // được tiêu mất mười giây của người dùng. Gõ nhầm tên ngôn ngữ rồi phải chờ 10 giây
        // để sửa là một hình phạt cho việc nhầm lẫn, không phải cho việc nộp quá nhanh.
        //
        // Trước persist: đây là chỗ duy nhất chặn được HAI request song song của cùng một
        // người. Đặt sau thì cả hai đã ghi xong, và giới hạn chỉ còn là một thông báo.
        rateLimiter.kiemTraVaGhiNhan(userId);

        // ---- M5 · Bước 5.4 — bài nộp thuộc kỳ thi nào -------------------------------------
        //
        // ★ SUY RA TỪ MÁY CHỦ, KHÔNG NHẬN TỪ CLIENT.
        //
        // Cách dễ là thêm một trường `contestId` vào request. Đừng: lúc đó client khai được
        // một contest khác, hoặc khai KHÔNG CÓ contest nào để bài của mình không vào bảng xếp
        // hạng — nộp thử trong giờ thi mà không bị tính penalty. Cả hai đều phá đúng thứ hệ
        // thống này bán.
        //
        // Chốt FR-CON-03 (đề ngoài giờ thì không nộp được) đã chạy ở `submittableById` phía
        // trên, nên tới đây đề chắc chắn mở với người này.
        Long contestId = lichThi.contestDangChayChuaDe(problem.id()).stream().boxed()
                .findFirst().orElse(null);

        long submissionId = persist(userId, contestId, problem, language, blob);
        publishQuietly(submissionId);
        return new SubmissionAccepted(submissionId, SubmissionStatus.QUEUED);
    }

    /**
     * Ba câu ghi, một transaction, ngân sách 50ms ({@code nfrplan.md} 2.1).
     *
     * <p>Thứ tự bắt buộc: blob trước (khoá ngoại của {@code submissions} trỏ vào nó), rồi
     * submission, rồi hàng đợi. Commit xong là bài <b>chắc chắn</b> được chấm — kể cả khi
     * RabbitMQ, Redis và toàn bộ worker đang chết.
     */
    private long persist(long userId, Long contestId, Problem problem,
                         LanguageRepository.Language language, SourceBlob blob) {
        return tx.execute(status -> {
            sourceBlobs.saveIfAbsent(blob);                          // ON CONFLICT DO NOTHING
            long id = submissions.insert(new SubmissionRepository.NewSubmission(
                    userId, problem.id(), contestId, language.id(),
                    blob.sha256(), blob.byteSize(), problem.currentTestdataVersion()));
            queue.enqueue(id, DomainRules.PRIORITY_LIVE);
            return id;
        });
        // ---- COMMIT ở đúng dấu ngoặc trên ----
    }

    /**
     * Publish hỏng thì <b>không rollback, không ném lỗi ra người dùng</b>: bài đã nằm trong
     * {@code judge_queue} với {@code claimed_at IS NULL}, và reaper sẽ nhặt nó.
     *
     * <p>Đây chính là lý do reaper tồn tại — một cơ chế cứu năm loại sự cố, và đây là loại
     * thứ nhất ({@code nfrplan.md} 5.1). Chaos test {@code PublishFailsButReaperRecoversIT}
     * kiểm đúng đường này.
     */
    private void publishQuietly(long submissionId) {
        try {
            events.publishEnqueued(submissionId);
        } catch (Exception e) {
            log.warn("publishEnqueued hỏng cho submission {} — bài vẫn trong judge_queue, "
                    + "reaper sẽ nhặt. Người dùng vẫn nhận 202.", submissionId, e);
        }
    }

    /**
     * Đầu vào của use-case.
     *
     * @param source mã nguồn thô — <b>không log trường này</b> (bất biến #9). Nó cũng là lý do
     *               record này không có {@code toString()} sinh sẵn nào được phép dùng: nó
     *               không bao giờ được đưa vào một dòng log
     */
    public record Command(long problemId, String languageCode, String source) {

        @Override
        public String toString() {
            return "Command[problemId=" + problemId + ", languageCode=" + languageCode
                    + ", sourceBytes=" + (source == null ? 0 : source.length()) + "]";
        }
    }

    /** Kết quả: {@code 202 Accepted} + hai trường này. Không có verdict, và sẽ không bao giờ có. */
    public record SubmissionAccepted(long submissionId, SubmissionStatus status) {
    }

    // -------------------------------------------------------------------------
    // Mọc thêm ở đây, theo đúng thứ tự, và mỗi cái phải trả lời "lấy đâu ra mili giây":
    //
    //   M4  FR-SUB-08 rate limit 1 bài/10s -> RateLimiter (platform.ratelimit), kiểm TRƯỚC
    //       mọi câu ghi. Redis chết thì lùi về SubmissionRepository.lastSubmittedAt.
    //   M4  FR-ADM-06 kill switch submissions.accepting đọc từ system_settings (có cache).
    //   M5  FR-CON-03 nộp bài trong contest -> contestId khác null, và kiểm khung giờ qua
    //       ContestWindowQuery đặt ở platform (luật ArchUnit 3 cấm judging -> contests).
    // -------------------------------------------------------------------------
}
