package dev.oj.problems.infrastructure;

import dev.oj.problems.application.port.ProblemAuthoringRepository;
import dev.oj.problems.domain.Problem;
import dev.oj.problems.domain.ProblemStatus;
import dev.oj.problems.domain.ProblemsException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;

/**
 * Đường soạn đề của SETTER/ADMIN — FR-PROB-01, 07, 08. Bước 4.9.
 *
 * <h2>File riêng, không phải một nửa của {@link JdbcProblemRepository}</h2>
 * Hai lý do, và lý do đầu quan trọng hơn:
 *
 * <ol>
 *   <li><b>Hai port là hai bất biến khác nhau.</b> File kia chỉ trả đề <i>đã xuất bản</i>;
 *       file này trả đề <i>của chính người gọi, kể cả chưa xuất bản</i>. Để chung thì một lần
 *       gọi nhầm hàm có thể lộ đề của contest tuần sau (FR-PROB-08), và không có gì trong tên
 *       file nhắc người đọc rằng hai nhóm hàm ấy khác nhau.</li>
 *   <li>File kia đã chạm trần 300 dòng của {@code CLAUDE.md} mục 7.</li>
 * </ol>
 *
 * <h2>{@code (:laAdmin OR owner_id = :requesterId)} là toàn bộ phép chống IDOR ở đây</h2>
 * Nó nằm <b>trong câu query</b>. Một câu {@code if (problem.ownerId() == user.id())} ở use-case
 * cũng chặn được, nhưng nó chặn <i>sau khi đã đọc cả đề lên bộ nhớ</i> — và ngày có người thêm
 * một dòng log hoặc một trường vào response trước câu {@code if} đó thì đề của người khác đã
 * ra ngoài ({@code oj-api/CLAUDE.md} mục 2, Bước 4.8).
 */
@Repository
public class JdbcProblemAuthoringRepository implements ProblemAuthoringRepository {



    private static final String FIND_FOR_AUTHOR = """
            SELECT id, code, title, statement_md,
                   time_limit_ms, memory_limit_kb, output_limit_kb,
                   checker_type, checker_epsilon, scoring_mode,
                   feedback_level, status, current_testdata_version,
                   owner_id, allow_public_solutions
              FROM problems
             WHERE lower(code) = lower(:code)
               AND (:laAdmin OR owner_id = :requesterId)
            """;

    private static final String FIND_FOR_AUTHOR_BY_ID = """
            SELECT id, code, title, statement_md,
                   time_limit_ms, memory_limit_kb, output_limit_kb,
                   checker_type, checker_epsilon, scoring_mode,
                   feedback_level, status, current_testdata_version,
                   owner_id, allow_public_solutions
              FROM problems
             WHERE id = :id
               AND (:laAdmin OR owner_id = :requesterId)
            """;

    /**
     * ★ {@code allow_public_solutions} phải có mặt ở ĐÂY, không chỉ ở {@link #CAP_NHAT}.
     *
     * <p>Bản đầu bỏ sót nó, nên cột nhận {@code DEFAULT FALSE} của V2 dù tác giả gửi
     * {@code true} — và vì {@code CAP_NHAT} thì lại ghi, lỗi chỉ hiện ra khi ai đó tạo đề với
     * cờ bật rồi đọc lại. Cột có {@code DEFAULT} là cột mà một câu INSERT thiếu vẫn chạy trót
     * lọt: không lỗi, không cảnh báo, chỉ là một giá trị khác thứ người dùng yêu cầu.
     */
    private static final String TAO_MOI = """
            INSERT INTO problems (code, title, statement_md, statement_hash,
                                  time_limit_ms, memory_limit_kb,
                                  checker_type, checker_epsilon, scoring_mode,
                                  feedback_level, owner_id, status,
                                  allow_public_solutions)
            VALUES (:code, :title, :statementMd, :statementHash,
                    :timeLimitMs, :memoryLimitKb,
                    :checkerType, :checkerEpsilon, :scoringMode,
                    :feedbackLevel, :ownerId, 'DRAFT',
                    :allowPublicSolutions)
            RETURNING id
            """;

    private static final String CAP_NHAT = """
            UPDATE problems
               SET title = :title,
                   statement_md = :statementMd,
                   statement_hash = :statementHash,
                   time_limit_ms = :timeLimitMs,
                   memory_limit_kb = :memoryLimitKb,
                   checker_type = :checkerType,
                   checker_epsilon = :checkerEpsilon,
                   scoring_mode = :scoringMode,
                   feedback_level = :feedbackLevel,
                   allow_public_solutions = :allowPublicSolutions
             WHERE id = :id
               AND (:laAdmin OR owner_id = :requesterId)
            """;

    /**
     * {@code published_at} dùng {@code COALESCE}: một đề gỡ xuống rồi đăng lại không phải một
     * đề mới, và {@code ck_problems_published} chỉ đòi nó khác NULL.
     */
    private static final String DOI_TRANG_THAI = """
            UPDATE problems
               SET status = :status,
                   published_at = CASE WHEN :status = 'PUBLISHED'
                                       THEN COALESCE(published_at, :luc)
                                       ELSE published_at END
             WHERE id = :id
               AND (:laAdmin OR owner_id = :requesterId)
            """;

    private final JdbcClient jdbc;

    public JdbcProblemAuthoringRepository(@Qualifier("appJdbcClient") JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Problem> findForAuthor(String code, long requesterId, boolean laAdmin) {
        return jdbc.sql(FIND_FOR_AUTHOR)
                .param("code", code)
                .param("laAdmin", laAdmin)
                .param("requesterId", requesterId)
                .query(JdbcProblemRepository.PROBLEM_MAPPER)
                .optional();
    }

    @Override
    public Optional<Problem> findForAuthorById(long id, long requesterId, boolean laAdmin) {
        return jdbc.sql(FIND_FOR_AUTHOR_BY_ID)
                .param("id", id)
                .param("laAdmin", laAdmin)
                .param("requesterId", requesterId)
                .query(JdbcProblemRepository.PROBLEM_MAPPER)
                .optional();
    }

    @Override
    public long taoMoi(NewProblem p) {
        try {
            return jdbc.sql(TAO_MOI)
                    .param("code", p.code())
                    .param("title", p.title())
                    .param("statementMd", p.statementMd())
                    .param("statementHash", p.statementHash())
                    .param("timeLimitMs", p.timeLimitMs())
                    .param("memoryLimitKb", p.memoryLimitKb())
                    .param("checkerType", p.checkerType().name().toLowerCase(Locale.ROOT))
                    .param("checkerEpsilon", p.checkerEpsilon())
                    .param("scoringMode", p.scoringMode().name())
                    .param("feedbackLevel", p.feedbackLevel().name())
                    .param("ownerId", p.ownerId())
                    .param("allowPublicSolutions", p.allowPublicSolutions())
                    .query(Long.class)
                    .single();
        } catch (DuplicateKeyException e) {
            throw ProblemsException.maDeDaTonTai(p.code());
        }
    }

    @Override
    public boolean capNhat(long id, ProblemEdit e, long requesterId, boolean laAdmin) {
        return jdbc.sql(CAP_NHAT)
                .param("title", e.title())
                .param("statementMd", e.statementMd())
                .param("statementHash", e.statementHash())
                .param("timeLimitMs", e.timeLimitMs())
                .param("memoryLimitKb", e.memoryLimitKb())
                .param("checkerType", e.checkerType().name().toLowerCase(Locale.ROOT))
                .param("checkerEpsilon", e.checkerEpsilon())
                .param("scoringMode", e.scoringMode().name())
                .param("feedbackLevel", e.feedbackLevel().name())
                .param("allowPublicSolutions", e.allowPublicSolutions())
                .param("id", id)
                .param("laAdmin", laAdmin)
                .param("requesterId", requesterId)
                .update() == 1;
    }

    @Override
    public boolean doiTrangThai(long id, ProblemStatus moi, long requesterId, boolean laAdmin,
                                Instant luc) {
        return jdbc.sql(DOI_TRANG_THAI)
                .param("status", moi.name())
                .param("luc", OffsetDateTime.ofInstant(luc, ZoneOffset.UTC))
                .param("id", id)
                .param("laAdmin", laAdmin)
                .param("requesterId", requesterId)
                .update() == 1;
    }

}
