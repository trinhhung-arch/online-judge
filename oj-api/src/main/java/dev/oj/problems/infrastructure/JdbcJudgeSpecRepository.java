package dev.oj.problems.infrastructure;

import dev.oj.contract.CheckerType;
import dev.oj.contract.ScoringMode;
import dev.oj.contract.SubtaskScoring;
import dev.oj.contract.SubtaskSpecDto;
import dev.oj.contract.TestcaseMetaDto;
import dev.oj.problems.application.port.JudgeSpecRepository;
import dev.oj.problems.domain.JudgeSpec;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Thông số chấm cho đường verdict. <b>Pool {@code judge}</b>, không phải {@code app} — đó là
 * toàn bộ lý do interface này tách khỏi {@code ProblemRepository}.
 *
 * <p>Nếu câu này chạy trên pool {@code app}, thì đúng lúc 500 người nộp bài cùng lúc — tức là
 * đúng lúc pool {@code app} cạn — worker sẽ không claim được job. Đó chính là kịch bản mà việc
 * tách hai pool sinh ra để ngăn ({@code postgres-design.md} mục 11), và nó quay lại qua cửa
 * sau chỉ vì một phương thức đặt nhầm chỗ.
 */
@Repository
public class JdbcJudgeSpecRepository implements JudgeSpecRepository {

    /**
     * Gắn với <b>đúng một phiên bản testdata</b>, lấy từ {@code submissions.testdata_version}
     * — không phải {@code problems.current_testdata_version}.
     *
     * <p>Sửa testdata tạo version mới chứ không ghi đè (FR-PROB-10). Dùng "mới nhất" ở đây
     * nghĩa là một lần rejudge sẽ âm thầm chấm bài cũ bằng bộ test mới, và không ai đối chiếu
     * được vì sao verdict đổi.
     */
    private static final String FIND_SPEC = """
            SELECT p.id, p.time_limit_ms, p.memory_limit_kb, p.output_limit_kb,
                   p.checker_type, p.checker_epsilon, p.scoring_mode,
                   tv.version, tv.manifest_sha256
              FROM problems p
              JOIN testdata_versions tv
                ON tv.problem_id = p.id
               AND tv.version    = :testdataVersion
             WHERE p.id = :problemId
            """;

    /**
     * Chỉ <b>metadata</b>: số thứ tự, cờ sample, và hai chuỗi sha256. Không có cột nào ở đây
     * chứa được nội dung test — bất biến #1 được ép ở tầng schema chứ không phải ở câu query
     * này ({@code postgres-design.md} mục 5).
     *
     * <p>{@code subtask_ordinal} là cột của V4 — {@code null} với đề không chia nhóm, và
     * chỉ là một SỐ THỨ TỰ với đề có chia. Nó nói test này thuộc nhóm nào, không nói gì về
     * nội dung test.
     */
    private static final String FIND_TESTCASES = """
            SELECT t.ordinal, t.is_sample, t.input_sha256, t.output_sha256,
                   s.ordinal AS subtask_ordinal
              FROM testcases t
              LEFT JOIN subtasks s ON s.id = t.subtask_id
             WHERE t.problem_id       = :problemId
               AND t.testdata_version = :testdataVersion
             ORDER BY t.ordinal
            """;

    /**
     * Nhóm test + phụ thuộc, gom trong MỘT câu.
     *
     * <p>Hai câu (nhóm, rồi phụ thuộc từng nhóm) là N+1 trên đường claim — đường mà mọi bài
     * nộp đều đi qua. {@code array_agg} gom phụ thuộc thành một mảng ngay trong Postgres.
     *
     * <p>{@code FILTER (WHERE ... IS NOT NULL)} là bắt buộc: {@code LEFT JOIN} không khớp cho
     * ra một hàng có {@code NULL}, và {@code array_agg} sẽ biến nó thành mảng {@code {NULL}}
     * — một nhóm không phụ thuộc gì bỗng có một phụ thuộc vào hư không.
     */
    private static final String FIND_SUBTASKS = """
            SELECT s.ordinal, s.points, s.scoring,
                   COALESCE(
                       array_agg(dep.ordinal ORDER BY dep.ordinal)
                           FILTER (WHERE dep.ordinal IS NOT NULL),
                       '{}') AS depends_on
              FROM subtasks s
              LEFT JOIN subtask_dependencies sd ON sd.subtask_id = s.id
              LEFT JOIN subtasks dep           ON dep.id = sd.depends_on_subtask_id
             WHERE s.problem_id       = :problemId
               AND s.testdata_version = :testdataVersion
             GROUP BY s.ordinal, s.points, s.scoring
             ORDER BY s.ordinal
            """;

    private final JdbcClient jdbc;

    public JdbcJudgeSpecRepository(@Qualifier("judgeJdbcClient") JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<JudgeSpec> findJudgeSpec(long problemId, int testdataVersion) {
        List<TestcaseMetaDto> testcases = jdbc.sql(FIND_TESTCASES)
                .param("problemId", problemId)
                .param("testdataVersion", testdataVersion)
                .query((rs, n) -> new TestcaseMetaDto(
                        rs.getInt("ordinal"),
                        rs.getBoolean("is_sample"),
                        rs.getString("input_sha256"),
                        rs.getString("output_sha256"),
                        (Integer) rs.getObject("subtask_ordinal")))
                .list();
        if (testcases.isEmpty()) {
            // Đề mất testdata ở đúng phiên bản bài nộp đã đóng dấu. Trả rỗng thay vì ném:
            // ClaimJudgeJobUseCase biến nó thành một dòng ERROR và bỏ qua job đó, chứ không
            // để một đề hỏng làm đứng cả hàng đợi. Xem javadoc skipBrokenJob().
            return Optional.empty();
        }
        List<SubtaskSpecDto> subtasks = jdbc.sql(FIND_SUBTASKS)
                .param("problemId", problemId)
                .param("testdataVersion", testdataVersion)
                .query((rs, n) -> new SubtaskSpecDto(
                        rs.getInt("ordinal"),
                        rs.getInt("points"),
                        SubtaskScoring.fromCode(rs.getString("scoring")),
                        dependsOn(rs.getArray("depends_on"))))
                .list();

        return jdbc.sql(FIND_SPEC)
                .param("problemId", problemId)
                .param("testdataVersion", testdataVersion)
                .query((rs, n) -> new JudgeSpec(
                        rs.getLong("id"),
                        rs.getInt("time_limit_ms"),
                        rs.getInt("memory_limit_kb"),
                        rs.getInt("output_limit_kb"),
                        CheckerType.fromCode(rs.getString("checker_type")),
                        checkerEpsilon(rs.getBigDecimal("checker_epsilon")),
                        ScoringMode.valueOf(rs.getString("scoring_mode")),
                        rs.getInt("version"),
                        rs.getString("manifest_sha256"),
                        subtasks,
                        testcases))
                .optional();
    }

    /** {@code smallint[]} của Postgres về Java là {@code Short[]}, không phải {@code Integer[]}. */
    private static List<Integer> dependsOn(java.sql.Array array) throws java.sql.SQLException {
        if (array == null) {
            return List.of();
        }
        Object[] values = (Object[]) array.getArray();
        List<Integer> ordinals = new java.util.ArrayList<>(values.length);
        for (Object value : values) {
            ordinals.add(((Number) value).intValue());
        }
        return List.copyOf(ordinals);
    }

    /** {@code ck_problems_epsilon} bảo đảm nó chỉ khác null khi checker là {@code float}. */
    private static BigDecimal checkerEpsilon(BigDecimal value) {
        return value;
    }
}
