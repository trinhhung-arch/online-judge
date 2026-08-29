package dev.oj.problems.infrastructure;

import dev.oj.problems.application.port.RenderedStatementRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Bảng {@code rendered_statements} (V2). Pool {@code app}.
 *
 * <p>{@code ON CONFLICT DO NOTHING} chứ không {@code DO UPDATE}: khoá là
 * {@code (statement_hash, renderer_version)}, nên hai bản ghi cùng khoá <b>có cùng nội dung
 * theo định nghĩa</b> — cả hai đều là kết quả của cùng một bộ render trên cùng một đầu vào.
 * Ghi đè chỉ tốn một lần ghi để có đúng thứ đang có.
 */
@Repository
public class JdbcRenderedStatementRepository implements RenderedStatementRepository {

    private static final String TIM = """
            SELECT html
              FROM rendered_statements
             WHERE statement_hash = :hash AND renderer_version = :version
            """;

    private static final String LUU = """
            INSERT INTO rendered_statements (statement_hash, renderer_version, html)
            VALUES (:hash, :version, :html)
            ON CONFLICT (statement_hash, renderer_version) DO NOTHING
            """;

    private final JdbcClient jdbc;

    public JdbcRenderedStatementRepository(@Qualifier("appJdbcClient") JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> tim(String statementHash, String rendererVersion) {
        return jdbc.sql(TIM)
                .param("hash", statementHash)
                .param("version", rendererVersion)
                .query(String.class)
                .optional();
    }

    @Override
    public void luu(String statementHash, String rendererVersion, String html) {
        jdbc.sql(LUU)
                .param("hash", statementHash)
                .param("version", rendererVersion)
                .param("html", html)
                .update();
    }
}
