package dev.oj.problems.infrastructure;

import dev.oj.contract.CheckerType;
import dev.oj.contract.ScoringMode;
import dev.oj.platform.web.CursorPage;
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
import java.util.List;
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

    // =========================================================================
    // Bước 4.9 — danh sách đề công khai (FR-PROB-09)
    //
    // Đường soạn đề của SETTER/ADMIN nằm ở JdbcProblemAuthoringRepository — file riêng,
    // vì hai port là hai bất biến khác nhau và file này đã chạm trần 300 dòng
    // (CLAUDE.md mục 7).
    // =========================================================================

    /**
     * FR-PROB-09. Ba bộ lọc tuỳ chọn viết theo khuôn {@code (:x IS NULL OR ...)} —
     * cùng cách {@code duong_nong.sql} truy vấn 6 làm, và là lý do luật ArchUnit 5b cấm nối
     * chuỗi mà vẫn không cản trở được việc lọc động.
     *
     * <p>{@code LIMIT :size + 1} lấy dư một dòng để biết còn trang sau hay không, mà không
     * phải chạy một câu {@code COUNT(*)} trên bảng đề.
     *
     * <p>★ <b>{@code CAST(:x AS kiểu)} quanh mỗi tham số tuỳ chọn là bắt buộc, không phải
     * trang trí.</b> Với một tham số {@code NULL} trần, Postgres không suy được kiểu từ ngữ
     * cảnh {@code :x IS NULL} và từ chối cả câu lệnh:
     * {@code could not determine data type of parameter $2}. Triệu chứng là 500 trên
     * <i>đúng</i> đường đi mặc định — khi người dùng không lọc gì cả — nên nó lọt qua mọi thử
     * nghiệm bằng tay có truyền bộ lọc.
     *
     * <p>Câu này chạm {@code submissions} — bảng của module {@code judging}. Luật ArchUnit 3
     * nói về phụ thuộc giữa các <b>package Java</b>, không về SQL, nên đây không phải vi phạm;
     * nhưng nó là một sợi dây thật, nên được viết ra ở đúng một chỗ và có tên
     * ({@code daGiai}), thay vì rải trong nhiều câu.
     */
    private static final String DANH_SACH = """
            SELECT p.id, p.code, p.title, p.time_limit_ms, p.memory_limit_kb,
                   EXISTS (SELECT 1 FROM submissions s
                            WHERE s.problem_id = p.id
                              AND s.user_id = :requesterId
                              AND s.verdict = 'AC') AS da_giai
              FROM problems p
             WHERE p.status = 'PUBLISHED'
               AND (CAST(:cursor AS bigint) IS NULL OR p.id < CAST(:cursor AS bigint))
               AND (CAST(:tagSlug AS text) IS NULL OR EXISTS (
                        SELECT 1 FROM problem_tags pt
                          JOIN tags t ON t.id = pt.tag_id
                         WHERE pt.problem_id = p.id AND t.slug = CAST(:tagSlug AS text)))
               AND (CAST(:daGiai AS boolean) IS NULL OR CAST(:daGiai AS boolean) = EXISTS (
                        SELECT 1 FROM submissions s2
                         WHERE s2.problem_id = p.id
                           AND s2.user_id = :requesterId
                           AND s2.verdict = 'AC'))
             ORDER BY p.id DESC
             LIMIT :limit
            """;

    @Override
    public CursorPage<ProblemListItem> danhSachDaXuatBan(ListFilter loc, Long cursor, int size) {
        List<ProblemListItem> duMotDong = jdbc.sql(DANH_SACH)
                .param("cursor", cursor)
                .param("tagSlug", loc.tagSlug())
                // Khách chưa đăng nhập: 0 không khớp users.id nào (GENERATED ALWAYS bắt đầu từ 1),
                // nên EXISTS luôn false và cột da_giai trả về false — đúng nghĩa "chưa giải".
                .param("requesterId", loc.requesterId() == null ? 0L : loc.requesterId())
                .param("daGiai", loc.daGiaiBoi())
                .param("limit", size + 1)
                .query((rs, i) -> new ProblemListItem(
                        rs.getLong("id"),
                        rs.getString("code"),
                        rs.getString("title"),
                        rs.getInt("time_limit_ms"),
                        rs.getInt("memory_limit_kb"),
                        rs.getBoolean("da_giai")))
                .list();

        boolean conTrangSau = duMotDong.size() > size;
        List<ProblemListItem> items = conTrangSau ? duMotDong.subList(0, size) : duMotDong;
        String next = conTrangSau ? String.valueOf(items.get(items.size() - 1).id()) : null;
        return new CursorPage<>(items, next);
    }
}
