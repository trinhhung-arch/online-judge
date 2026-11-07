package dev.oj.platform.settings.infrastructure;

import dev.oj.platform.settings.SystemSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class JdbcSystemSettings implements SystemSettings {

    private static final Logger log = LoggerFactory.getLogger(JdbcSystemSettings.class);

    private static final Duration TTL = Duration.ofSeconds(2);

    private static final String DOC = """
            SELECT value #>> '{}' FROM system_settings WHERE key = :khoa
            """;

    private static final String GHI = """
            INSERT INTO system_settings (key, value, updated_by, updated_at)
            VALUES (:khoa, CAST(:giaTri AS jsonb), :nguoiDoi, now())
            ON CONFLICT (key) DO UPDATE
               SET value = EXCLUDED.value,
                   updated_by = EXCLUDED.updated_by,
                   updated_at = EXCLUDED.updated_at
            """;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public JdbcSystemSettings(@Qualifier("appJdbcClient") JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public boolean bat(String khoa, boolean macDinh) {
        Cached cu = cache.get(khoa);
        Instant bayGio = clock.instant();
        if (cu != null && cu.hetHan().isAfter(bayGio)) {
            return cu.giaTri() == null ? macDinh : cu.giaTri();
        }
        Boolean giaTri = docTuDb(khoa);
        cache.put(khoa, new Cached(giaTri, bayGio.plus(TTL)));
        return giaTri == null ? macDinh : giaTri;
    }

    private Boolean docTuDb(String khoa) {
        try {
            return jdbc.sql(DOC).param("khoa", khoa)
                    .query(String.class).optional()
                    .map(Boolean::parseBoolean)
                    .orElse(null);
        } catch (RuntimeException e) {
            log.warn("Không đọc được công tắc '{}': {}. Người gọi dùng mặc định của mình.",
                    khoa, e.toString());
            return null;
        }
    }

    @Override
    public void dat(String khoa, boolean giaTri, Long nguoiDoi) {
        jdbc.sql(GHI)
                .param("khoa", khoa)
                .param("giaTri", Boolean.toString(giaTri))
                .param("nguoiDoi", nguoiDoi)
                .update();
        cache.remove(khoa);
    }

    private record Cached(Boolean giaTri, Instant hetHan) {
    }
}
