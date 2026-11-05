package dev.oj.identity.infrastructure;

import dev.oj.identity.application.port.LoginAttemptRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

@Repository
public class JdbcLoginAttemptRepository implements LoginAttemptRepository {

    private static final String GHI_NHAN = """
            INSERT INTO login_attempts (handle_tried, client_ip, succeeded)
            VALUES (:handle, CAST(:clientIp AS inet), :thanhCong)
            """;

    private static final String DEM_THAT_BAI = """
            SELECT count(*)
              FROM login_attempts
             WHERE client_ip <<= CAST(:dai AS inet)
               AND NOT succeeded
               AND attempted_at >= :moc
            """;

    private static final String KHOA_TOI = """
            SELECT locked_until
              FROM login_lockouts
             WHERE client_ip >>= CAST(:clientIp AS inet)
             ORDER BY locked_until DESC
             LIMIT 1
            """;

    private static final String KHOA = """
            INSERT INTO login_lockouts (client_ip, locked_until, reason)
            VALUES (CAST(:dai AS inet), :toi, :lyDo)
            ON CONFLICT (client_ip) DO UPDATE
               SET locked_until = EXCLUDED.locked_until,
                   reason = EXCLUDED.reason,
                   created_at = now()
            """;

    private final JdbcClient jdbc;

    public JdbcLoginAttemptRepository(@Qualifier("appJdbcClient") JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void ghiNhan(String handleDaThu, String clientIp, boolean thanhCong) {
        jdbc.sql(GHI_NHAN)
                .param("handle", cat(handleDaThu))
                .param("clientIp", clientIp)
                .param("thanhCong", thanhCong)
                .update();
    }

    @Override
    public int demThatBaiTu(String dai, Instant moc) {
        return jdbc.sql(DEM_THAT_BAI)
                .param("dai", dai)
                .param("moc", OffsetDateTime.ofInstant(moc, ZoneOffset.UTC))
                .query(Integer.class)
                .single();
    }

    @Override
    public Optional<Instant> khoaToi(String clientIp) {
        return jdbc.sql(KHOA_TOI)
                .param("clientIp", clientIp)
                .query(OffsetDateTime.class)
                .optional()
                .map(OffsetDateTime::toInstant);
    }

    @Override
    public void khoa(String dai, Instant toi, String lyDo) {
        jdbc.sql(KHOA)
                .param("dai", dai)
                .param("toi", OffsetDateTime.ofInstant(toi, ZoneOffset.UTC))
                .param("lyDo", lyDo)
                .update();
    }

    private static String cat(String handle) {
        if (handle == null) {
            return "";
        }
        return handle.length() <= 64 ? handle : handle.substring(0, 64);
    }
}
