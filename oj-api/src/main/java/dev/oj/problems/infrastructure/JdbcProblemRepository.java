package dev.oj.problems.infrastructure;

import dev.oj.contract.CheckerType;
import dev.oj.contract.ScoringMode;
import dev.oj.problems.application.port.ProblemRepository;
import dev.oj.problems.domain.FeedbackLevel;
import dev.oj.problems.domain.Problem;
import dev.oj.problems.domain.ProblemStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Đọc đề cho request người dùng. Chạy trên pool {@code app}.
 *
 * <h2>Ba quy ước của mọi repository trong dự án</h2>
 * <ol>
 *   <li><b>Không {@code SELECT *}.</b> {@code problems.statement_md} là một cột {@code TEXT}
 *       lớn; một ngày nào đó có người thêm cột nữa, và mọi truy vấn danh sách chậm đi mà không
 *       ai hay ({@code postgres-design.md} mục 15).</li>
 *   <li><b>Named parameter, và câu SQL là hằng số viết trọn vẹn.</b> Bất biến #5. Kể cả ghép
 *       một danh sách cột bằng {@code String.formatted} cũng không làm — nó không tạo lỗ hổng
 *       nào ở đây, nhưng nó khiến câu SQL không còn {@code grep} ra được nguyên văn, và nó
 *       làm mờ ranh giới mà luật ArchUnit 5b đang giữ. Hai câu dưới đây lặp lại danh sách cột,
 *       và sự lặp đó là cố ý.</li>
 *   <li><b>Bộ lọc trạng thái nằm trong câu query</b>, không phải trong một câu {@code if} sau
 *       khi đã load.</li>
 * </ol>
 */
@Repository
public class JdbcProblemRepository implements ProblemRepository {

    /**
     * {@code lower(code) = lower(:code)} — viết đúng dạng này để trúng index biểu thức
     * {@code ux_problems_code_lower} ở V1. Viết {@code code ILIKE :code} thì index không dùng
     * được và câu query thành seq scan.
     */
    private static final String FIND_BY_CODE = """
            SELECT id, code, title, statement_md,
                   time_limit_ms, memory_limit_kb, output_limit_kb,
                   checker_type, checker_epsilon, scoring_mode,
                   feedback_level, status, current_testdata_version,
                   owner_id, allow_public_solutions
              FROM problems
             WHERE lower(code) = lower(:code)
               AND status IN ('PUBLISHED', 'RETIRED')
            """;

    private static final String FIND_BY_ID = """
            SELECT id, code, title, statement_md,
                   time_limit_ms, memory_limit_kb, output_limit_kb,
                   checker_type, checker_epsilon, scoring_mode,
                   feedback_level, status, current_testdata_version,
                   owner_id, allow_public_solutions
              FROM problems
             WHERE id = :id
               AND status IN ('PUBLISHED', 'RETIRED')
            """;

    private final JdbcClient jdbc;

    /**
     * Pool {@code app}, cố ý. Đường verdict đọc thông số chấm qua
     * {@link JdbcJudgeSpecRepository} trên pool {@code judge} — xem javadoc ở đó.
     */
    public JdbcProblemRepository(@Qualifier("appJdbcClient") JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Problem> findPublishedByCode(String code) {
        return jdbc.sql(FIND_BY_CODE)
                .param("code", code)
                .query(PROBLEM_MAPPER)
                .optional();
    }

    @Override
    public Optional<Problem> findPublishedById(long id) {
        return jdbc.sql(FIND_BY_ID)
                .param("id", id)
                .query(PROBLEM_MAPPER)
                .optional();
    }

    /**
     * Ánh xạ tay thay vì {@code query(Problem.class)}.
     *
     * <p>Không phải vì thích dài dòng: {@code checker_type} lưu chữ thường ({@code 'token'})
     * còn hằng enum là {@code TOKEN}, nên bộ chuyển đổi tự động của Spring sẽ ném lỗi lúc
     * chạy — và ném ở tận request đầu tiên chứ không phải lúc biên dịch. Ánh xạ tay khiến
     * chỗ khác biệt đó hiện ra ngay trên màn hình.
     */
    static final RowMapper<Problem> PROBLEM_MAPPER = JdbcProblemRepository::mapProblem;

    private static Problem mapProblem(ResultSet rs, int rowNum) throws SQLException {
        return new Problem(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("title"),
                rs.getString("statement_md"),
                rs.getInt("time_limit_ms"),
                rs.getInt("memory_limit_kb"),
                rs.getInt("output_limit_kb"),
                CheckerType.fromCode(rs.getString("checker_type")),
                rs.getBigDecimal("checker_epsilon"),
                ScoringMode.valueOf(rs.getString("scoring_mode")),
                FeedbackLevel.fromCode(rs.getString("feedback_level")),
                ProblemStatus.fromCode(rs.getString("status")),
                rs.getInt("current_testdata_version"),
                rs.getLong("owner_id"),
                rs.getBoolean("allow_public_solutions"));
    }

    // -------------------------------------------------------------------------
    // M4 thêm vào đây, không sửa hai hàm trên:
    //   findForAuthor(code, requesterId, role)  -> lấy CẢ đề DRAFT, nhưng chỉ của
    //   chính người đó (hoặc mọi đề nếu ADMIN). Điều kiện chủ sở hữu phải nằm TRONG
    //   câu query: `AND (owner_id = :requesterId OR :role = 'ADMIN')`.
    //   Đừng nới lỏng FIND_BY_CODE rồi lọc bằng if — đó là mẫu tạo ra lỗ hổng IDOR
    //   ngay cả khi câu if viết đúng (oj-api/CLAUDE.md mục 2).
    // -------------------------------------------------------------------------
}
