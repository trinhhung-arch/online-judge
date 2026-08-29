package dev.oj.judging.infrastructure;

import dev.oj.judging.application.port.SubmissionRepository;
import dev.oj.judging.application.port.SubmissionRepository.SubmissionDetail;
import dev.oj.judging.domain.JudgeOutcome;
import dev.oj.judging.domain.Submission;
import dev.oj.platform.security.Role;
import dev.oj.platform.web.CursorPage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Bảng nóng {@code submissions}. Truy vấn 1b · 2 (phần submissions) · 3c · 4 (phần submissions)
 * · 6 · 7 · 9.
 *
 * <h2>★ Class duy nhất giữ CẢ HAI {@code JdbcClient}, và mỗi phương thức chỉ dùng một</h2>
 * {@code JdbcConfig} đã ghi trước điều này. Lý do là bảng {@code submissions} bị chạm từ hai
 * đường có hai ngân sách và hai pool khác nhau:
 *
 * <pre>
 *   POOL   PHƯƠNG THỨC                              TRANSACTION CỦA
 *   app    insert · findForRequester · listForUser   @Transactional  (request người dùng)
 *          · lastSubmittedAt
 *   judge  markJudging · markDone · markQueued       @JudgeTransactional (đường verdict)
 * </pre>
 *
 * <p><b>Dùng nhầm client là hỏng im lặng, không phải hỏng ồn ào.</b> Một câu {@code UPDATE}
 * chạy qua {@code appJdbc} bên trong {@code @JudgeTransactional} sẽ mượn một connection khác,
 * <i>ngoài</i> transaction đang mở — nó tự commit ngay và <b>không bị rollback</b> nếu phần
 * còn lại thất bại. Lúc đó khoá lạc quan, {@code INSERT judge_runs} và {@code UPDATE
 * submissions} rời nhau ra, và R2 ("0 bài bị chấm 2 lần") vỡ mà không có ngoại lệ nào được ném.
 *
 * <p>Vì thế mỗi phương thức dưới đây mở đầu bằng một dòng ghi rõ pool của nó. Nếu bạn thêm
 * phương thức mới mà không viết được dòng đó, nghĩa là bạn chưa biết nó thuộc đường nào.
 */
@Repository
public class JdbcSubmissionRepository implements SubmissionRepository {

    // ---- truy vấn 1b ----
    private static final String INSERT = """
            INSERT INTO submissions (user_id, problem_id, contest_id, language_id,
                                     source_sha256, source_bytes, testdata_version)
            VALUES (:userId, :problemId, :contestId, :languageId,
                    :sha256, :byteSize, :testdataVersion)
            RETURNING id
            """;

    /**
     * Truy vấn 9 — chống IDOR. Điều kiện chủ sở hữu nằm <b>trong</b> câu query.
     *
     * <p>Hai khác biệt so với bản trong {@code duong_nong.sql}, cả hai có chủ ý:
     * <ul>
     *   <li>Lấy đủ cột để dựng {@link Submission} thay vì hình chiếu bốn cột — use-case trả
     *       về entity domain, và việc lọc {@code failed_test_ordinal} theo
     *       {@code feedback_level} là chuyện của M3 ở tầng {@code api}, không phải của câu này.</li>
     *   <li>Thêm {@code hidden_at IS NULL OR ADMIN}: FR-SUB-09 nói ADMIN <i>ẩn</i> bài, và ẩn
     *       thì phải ẩn cả với tác giả — nếu không thì "ẩn" chỉ là ẩn khỏi danh sách.</li>
     * </ul>
     */
    private static final String FIND_FOR_REQUESTER = """
            SELECT id, user_id, problem_id, contest_id, language_id,
                   source_sha256, source_bytes, created_at,
                   status, attempt, testdata_version,
                   verdict, score, max_score, failed_test_ordinal,
                   time_ms, memory_kb, judged_at, hidden_at, hidden_by
              FROM submissions
             WHERE id = :id
               AND (user_id = :requesterId OR :requesterRole = 'ADMIN')
               AND (hidden_at IS NULL      OR :requesterRole = 'ADMIN')
            """;

    /**
     * Trang chi tiết — bài nộp + đề + ngôn ngữ + lần chấm hiện tại, trong MỘT câu.
     *
     * <p>{@code LEFT JOIN judge_runs}: bài đang {@code QUEUED} chưa có bản ghi chấm nào, và
     * đó không phải lỗi. {@code ON r.attempt = s.attempt} chứ không phải "attempt lớn nhất" —
     * trang phải hiện log của <b>lần chấm hiện tại</b>, không phải của một lần rejudge cũ.
     *
     * <p>Hệ số ngôn ngữ trả về THÔ ({@code time_multiplier}, {@code startup_overhead_ms}) chứ
     * không nhân sẵn trong SQL: công thức đã có một bản duy nhất ở
     * {@code JudgeSpec.timeLimitOnReferenceHost}, và viết lại nó bằng SQL là dựng bản thứ hai.
     */
    private static final String FIND_DETAIL_FOR_REQUESTER = """
            SELECT s.id, s.user_id, s.problem_id, s.contest_id, s.language_id,
                   s.source_sha256, s.source_bytes, s.created_at,
                   s.status, s.attempt, s.testdata_version,
                   s.verdict, s.score, s.max_score, s.failed_test_ordinal,
                   s.time_ms, s.memory_kb, s.judged_at, s.hidden_at, s.hidden_by,
                   p.feedback_level, p.time_limit_ms, p.memory_limit_kb,
                   l.time_multiplier, l.startup_overhead_ms, l.memory_overhead_kb,
                   r.compile_log, r.isolate_status
              FROM submissions s
              JOIN problems  p ON p.id = s.problem_id
              JOIN languages l ON l.id = s.language_id
              LEFT JOIN judge_runs r
                     ON r.submission_id = s.id
                    AND r.attempt       = s.attempt
             WHERE s.id = :id
               AND (s.user_id  = :requesterId OR :requesterRole = 'ADMIN')
               AND (s.hidden_at IS NULL       OR :requesterRole = 'ADMIN')
            """;

    /**
     * Truy vấn 6 — cursor-based, không {@code OFFSET}, không {@code COUNT(*)} (bất biến #8).
     *
     * <p>Mẫu {@code (:x::KIEU IS NULL OR cot = :x)} giữ câu SQL là một <b>hằng số</b>: cùng một
     * chuỗi cho mọi tổ hợp bộ lọc, nên planner còn dùng lại được prepared statement và ta không
     * bao giờ phải dựng mệnh đề {@code WHERE} bằng cách nối chuỗi (bất biến #5). Ép kiểu
     * {@code ::BIGINT} là bắt buộc: không có nó, Postgres không suy ra được kiểu của một tham
     * số {@code NULL} và câu lệnh hỏng ngay.
     */
    private static final String LIST_FOR_USER = """
            SELECT s.id, s.problem_id, p.code AS problem_code, p.title AS problem_title,
                   s.language_id, s.status, s.verdict, s.score,
                   s.time_ms, s.memory_kb, s.created_at
              FROM submissions s
              JOIN problems p ON p.id = s.problem_id
             WHERE s.user_id = :userId
               AND (:cursorId::BIGINT     IS NULL OR s.id          <  :cursorId)
               AND (:problemId::BIGINT    IS NULL OR s.problem_id  =  :problemId)
               AND (:verdict::TEXT        IS NULL OR s.verdict     =  :verdict)
               AND (:languageId::SMALLINT IS NULL OR s.language_id =  :languageId)
               AND s.hidden_at IS NULL
             ORDER BY s.id DESC
             LIMIT :pageSize
            """;

    // ---- truy vấn 7 — index-only scan trên ix_submissions_user_recent ----
    private static final String LAST_SUBMITTED_AT = """
            SELECT created_at
              FROM submissions
             WHERE user_id = :userId
             ORDER BY id DESC
             LIMIT 1
            """;

    // ---- truy vấn 2, phần submissions. HOT update: không cột nào được index bị đổi ----
    private static final String MARK_JUDGING = """
            UPDATE submissions
               SET status = 'JUDGING', attempt = :attempt
             WHERE id = :id
            """;

    /**
     * Truy vấn 3c. {@code AND attempt = :attempt AND status = 'JUDGING'} là <b>lớp bảo vệ thứ
     * ba</b> của bất biến #7, sau khoá lạc quan trên {@code judge_queue} và khoá chính của
     * {@code judge_runs}. Nó bắt đúng một loại lỗi mà hai lớp kia không thấy: một đường ghi
     * verdict mới nào đó gọi thẳng vào đây mà không đi qua khoá lạc quan.
     *
     * <p>{@code judged_at} nhận từ {@code Clock} của ứng dụng chứ không dùng {@code now()} như
     * bản trong {@code duong_nong.sql} — để {@code RecordJudgeResultUseCase} test được bằng
     * {@code Clock.fixed} mà không cần DB.
     */
    private static final String MARK_DONE = """
            UPDATE submissions
               SET status              = 'DONE',
                   verdict             = :verdict,
                   score               = :score,
                   max_score           = :maxScore,
                   failed_test_ordinal = :failedTestOrdinal,
                   time_ms             = :timeMs,
                   memory_kb           = :memoryKb,
                   judged_at           = :judgedAt
             WHERE id      = :id
               AND attempt = :attempt
               AND status  = 'JUDGING'
            """;

    // ---- truy vấn 4, phần submissions. attempt KHÔNG đổi ở đây ----
    private static final String MARK_QUEUED = """
            UPDATE submissions
               SET status = 'QUEUED'
             WHERE id IN (:ids)
            """;

    /**
     * ★ Driver Postgres <b>không bind được {@link Instant}</b>: nó ném
     * {@code "Can't infer the SQL type to use for an instance of java.time.Instant"} ngay lúc
     * chạy. Không trình biên dịch nào cảnh báo, và không unit test nào với repository giả bắt
     * được — nó chỉ hiện ra khi có một Postgres thật ở đầu kia.
     *
     * <p>{@code OffsetDateTime} thì driver hiểu, và {@code timestamptz} lưu đúng thời điểm
     * tuyệt đối. Dùng UTC vì mọi cột thời gian trong schema là {@code TIMESTAMPTZ} và máy dev
     * (WSL, giờ VN) khác host (Mac) — xem {@code ClockConfig}.
     */
    static OffsetDateTime timestamptz(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private final JdbcClient appJdbc;
    private final JdbcClient judgeJdbc;

    public JdbcSubmissionRepository(@Qualifier("appJdbcClient") JdbcClient appJdbc,
                                    @Qualifier("judgeJdbcClient") JdbcClient judgeJdbc) {
        this.appJdbc = appJdbc;
        this.judgeJdbc = judgeJdbc;
    }

    @Override
    public long insert(NewSubmission s) {
        // POOL app — trong transaction nộp bài.
        return appJdbc.sql(INSERT)
                .param("userId", s.userId())
                .param("problemId", s.problemId())
                .param("contestId", s.contestId())
                .param("languageId", s.languageId())
                .param("sha256", s.sourceSha256())
                .param("byteSize", s.sourceBytes())
                .param("testdataVersion", s.testdataVersion())
                .query(Long.class)
                .single();
    }

    @Override
    public Optional<Submission> findForRequester(long id, long requesterId, Role role) {
        // POOL app — request người dùng.
        return appJdbc.sql(FIND_FOR_REQUESTER)
                .param("id", id)
                .param("requesterId", requesterId)
                .param("requesterRole", role.name())
                .query(SubmissionRowMappers.SUBMISSION)
                .optional();
    }

    @Override
    public Optional<SubmissionDetail> findDetailForRequester(long id, long requesterId, Role role) {
        return appJdbc.sql(FIND_DETAIL_FOR_REQUESTER)
                .param("id", id)
                .param("requesterId", requesterId)
                .param("requesterRole", role.name())
                .query(SubmissionRowMappers.DETAIL)
                .optional();
    }

    @Override
    public CursorPage<SubmissionListItem> listForUser(long userId, SubmissionFilter filter,
                                                      Long cursor, int size) {
        // POOL app — request người dùng.
        // Xin dư ĐÚNG MỘT dòng để biết còn trang sau mà không phải chạy thêm câu đếm.
        List<SubmissionListItem> rows = appJdbc.sql(LIST_FOR_USER)
                .param("userId", userId)
                .param("cursorId", cursor)
                .param("problemId", filter.problemId())
                .param("verdict", filter.verdict() == null ? null : filter.verdict().name())
                .param("languageId", filter.languageId())
                .param("pageSize", size + 1)
                .query(SubmissionRowMappers.LIST_ITEM)
                .list();
        return CursorPage.of(rows, size, item -> String.valueOf(item.id()));
    }

    @Override
    public Optional<Instant> lastSubmittedAt(long userId) {
        // POOL app — đường dự phòng của rate limit khi Redis chết (M4).
        return appJdbc.sql(LAST_SUBMITTED_AT)
                .param("userId", userId)
                .query(Instant.class)
                .optional();
    }

    @Override
    public boolean markJudging(long submissionId, int attempt) {
        // POOL judge — @JudgeTransactional.
        return judgeJdbc.sql(MARK_JUDGING)
                .param("id", submissionId)
                .param("attempt", attempt)
                .update() == 1;
    }

    @Override
    public boolean markDone(long submissionId, int attempt, JudgeOutcome outcome, Instant judgedAt) {
        // POOL judge — @JudgeTransactional, sau khoá lạc quan.
        return judgeJdbc.sql(MARK_DONE)
                .param("id", submissionId)
                .param("attempt", attempt)
                .param("verdict", outcome.verdict().name())
                .param("score", outcome.score())
                .param("maxScore", outcome.maxScore())
                .param("failedTestOrdinal", outcome.failedTestOrdinal())
                .param("timeMs", outcome.timeMs())
                .param("memoryKb", outcome.memoryKb())
                .param("judgedAt", timestamptz(judgedAt))
                .update() == 1;
    }

    @Override
    public int markQueued(Collection<Long> submissionIds) {
        // POOL judge — @JudgeTransactional, cùng transaction với reapExpired().
        if (submissionIds.isEmpty()) {
            return 0;   // `IN ()` là SQL không hợp lệ; và không có gì để làm thì đừng đi hỏi DB
        }
        return judgeJdbc.sql(MARK_QUEUED)
                .param("ids", submissionIds)
                .update();
    }
}
