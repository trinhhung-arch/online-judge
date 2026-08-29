package dev.oj.judging.infrastructure;

import dev.oj.judging.application.port.LanguageRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Tra ngôn ngữ trên đường <b>nộp bài</b> (pool {@code app}).
 *
 * <p>Không có trong danh sách 12 truy vấn nóng vì nó không nằm trên đường verdict — đường đó
 * lấy thông số ngôn ngữ qua {@code JOIN} ngay trong câu claim, không gọi lại repository này.
 */
@Repository
public class JdbcLanguageRepository implements LanguageRepository {

    /**
     * {@code AND enabled} nằm trong câu query, khớp với chữ "Enabled" trong tên phương thức.
     *
     * <p>Tắt một ngôn ngữ là thao tác vận hành có thật: toolchain hỏng sau một lần cập nhật
     * máy chấm, và ta muốn ngừng nhận bài bằng một dòng UPDATE chứ không phải một lần deploy.
     * Quên điều kiện này nghĩa là hệ thống vẫn nhận bài bằng một toolchain không còn tồn tại,
     * và mọi bài đều ra {@code IE} mà người dùng không hiểu vì sao.
     */
    private static final String FIND_ENABLED_BY_CODE = """
            SELECT id, code
              FROM languages
             WHERE code = :code
               AND enabled
            """;

    private final JdbcClient jdbc;

    public JdbcLanguageRepository(@Qualifier("appJdbcClient") JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Language> findEnabledByCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();     // khỏi tốn một lượt round-trip cho một chuỗi rác
        }
        return jdbc.sql(FIND_ENABLED_BY_CODE)
                .param("code", code)
                .query((rs, n) -> new Language(rs.getInt("id"), rs.getString("code")))
                .optional();
    }

    /**
     * Ba dòng, sắp theo tên hiển thị. Không {@code SELECT *} — bảng {@code languages} có cả
     * {@code compile_command} và {@code run_command}, và chúng là chuyện của worker.
     */
    private static final String LIET_KE = """
            SELECT code, display_name, version_label
              FROM languages
             WHERE enabled
             ORDER BY display_name
            """;

    @Override
    public java.util.List<LanguageOption> listEnabled() {
        return jdbc.sql(LIET_KE)
                .query((rs, i) -> new LanguageOption(
                        rs.getString("code"),
                        rs.getString("display_name"),
                        rs.getString("version_label")))
                .list();
    }
}
