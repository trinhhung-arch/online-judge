package dev.oj.judging.infrastructure;

import dev.oj.judging.application.port.LanguageRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class JdbcLanguageRepository implements LanguageRepository {

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
            return Optional.empty();
        }
        return jdbc.sql(FIND_ENABLED_BY_CODE)
                .param("code", code)
                .query((rs, n) -> new Language(rs.getInt("id"), rs.getString("code")))
                .optional();
    }

    private static final String LIET_KE = """
            SELECT id, code, display_name, version_label
              FROM languages
             WHERE enabled
             ORDER BY display_name
            """;

    @Override
    public java.util.List<LanguageOption> listEnabled() {
        return jdbc.sql(LIET_KE)
                .query((rs, i) -> new LanguageOption(
                        rs.getShort("id"),
                        rs.getString("code"),
                        rs.getString("display_name"),
                        rs.getString("version_label")))
                .list();
    }
}
