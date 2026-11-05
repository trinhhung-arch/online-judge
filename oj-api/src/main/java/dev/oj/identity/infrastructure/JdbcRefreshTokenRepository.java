package dev.oj.identity.infrastructure;

import dev.oj.identity.application.port.RefreshTokenRepository;
import dev.oj.identity.domain.RefreshToken;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
public class JdbcRefreshTokenRepository implements RefreshTokenRepository {

    private static final String LUU = """
            INSERT INTO refresh_tokens (user_id, token_sha256, issued_at, expires_at,
                                        user_agent, client_ip)
            VALUES (:userId, :tokenSha256, :issuedAt, :expiresAt,
                    :userAgent, CAST(:clientIp AS inet))
            RETURNING id
            """;

    private static final String TIM_THEO_BAM = """
            SELECT id, user_id, issued_at, expires_at, revoked_at
              FROM refresh_tokens
             WHERE token_sha256 = :tokenSha256
            """;

    private static final String THU_HOI = """
            UPDATE refresh_tokens
               SET revoked_at = now(), revoked_reason = :lyDo, replaced_by_id = :thayThe
             WHERE id = :id AND revoked_at IS NULL
            """;

    private static final String GAN_THAY_THE = """
            UPDATE refresh_tokens SET replaced_by_id = :thayThe WHERE id = :id
            """;

    private static final String THU_HOI_TAT_CA = """
            UPDATE refresh_tokens
               SET revoked_at = now(), revoked_reason = :lyDo
             WHERE user_id = :userId AND revoked_at IS NULL
            """;

    private final JdbcClient jdbc;

    public JdbcRefreshTokenRepository(@Qualifier("appJdbcClient") JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long luu(long userId, String tokenSha256, Instant phatLuc, Instant hetHan,
                    String userAgent, String clientIp) {
        return jdbc.sql(LUU)
                .param("userId", userId)
                .param("tokenSha256", tokenSha256)
                .param("issuedAt", OffsetDateTime.ofInstant(phatLuc, java.time.ZoneOffset.UTC))
                .param("expiresAt", OffsetDateTime.ofInstant(hetHan, java.time.ZoneOffset.UTC))
                .param("userAgent", cat(userAgent))
                .param("clientIp", clientIp)
                .query(Long.class)
                .single();
    }

    @Override
    public Optional<RefreshToken> timTheoBam(String tokenSha256) {
        return jdbc.sql(TIM_THEO_BAM).param("tokenSha256", tokenSha256).query(TOKEN).optional();
    }

    @Override
    public boolean thuHoi(long tokenId, String lyDo, Long thayTheBoiId) {
        return jdbc.sql(THU_HOI)
                .param("lyDo", lyDo)
                .param("thayThe", thayTheBoiId)
                .param("id", tokenId)
                .update() == 1;
    }

    @Override
    public void ganThayThe(long tokenId, long thayTheBoiId) {
        jdbc.sql(GAN_THAY_THE).param("thayThe", thayTheBoiId).param("id", tokenId).update();
    }

    @Override
    public int thuHoiTatCa(long userId, String lyDo) {
        return jdbc.sql(THU_HOI_TAT_CA).param("lyDo", lyDo).param("userId", userId).update();
    }

    private static String cat(String userAgent) {
        if (userAgent == null) {
            return null;
        }
        return userAgent.length() <= 256 ? userAgent : userAgent.substring(0, 256);
    }

    private static final RowMapper<RefreshToken> TOKEN = (rs, i) -> new RefreshToken(
            rs.getLong("id"),
            rs.getLong("user_id"),
            rs.getObject("issued_at", OffsetDateTime.class).toInstant(),
            rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
            thoiDiem(rs));

    private static Instant thoiDiem(ResultSet rs) throws SQLException {
        OffsetDateTime value = rs.getObject("revoked_at", OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
