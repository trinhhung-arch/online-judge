package dev.oj.identity.infrastructure;

import dev.oj.identity.application.port.EmailVerificationRepository;
import dev.oj.identity.domain.IdentityException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

@Repository
public class JdbcEmailVerificationRepository implements EmailVerificationRepository {

    private static final String TIM_MA_DANG_SONG = """
            SELECT id, code_sha256, created_at, attempts
              FROM email_verifications
             WHERE user_id = :userId
               AND consumed_at IS NULL
               AND expires_at > now()
            """;

    private static final String HUY_MA_CU = """
            UPDATE email_verifications SET consumed_at = now()
             WHERE user_id = :userId AND consumed_at IS NULL
            """;

    private static final String LUU = """
            INSERT INTO email_verifications (user_id, code_sha256, expires_at)
            VALUES (:userId, :maSha256, :hetHan)
            RETURNING id
            """;

    private static final String TIEU_THU = """
            UPDATE email_verifications SET consumed_at = now()
             WHERE id = :id
               AND consumed_at IS NULL
               AND expires_at > now()
            """;

    private static final String GHI_NHAN_THU_SAI = """
            UPDATE email_verifications SET attempts = attempts + 1
             WHERE id = :id AND consumed_at IS NULL
            RETURNING attempts
            """;

    private final JdbcClient jdbc;

    public JdbcEmailVerificationRepository(@Qualifier("appJdbcClient") JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<MaDangSong> timMaDangSong(long userId) {
        return jdbc.sql(TIM_MA_DANG_SONG)
                .param("userId", userId)
                .query((rs, n) -> new MaDangSong(
                        rs.getLong("id"),
                        rs.getString("code_sha256"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getInt("attempts")))
                .optional();
    }

    @Override
    public int huyMaCu(long userId) {
        return jdbc.sql(HUY_MA_CU).param("userId", userId).update();
    }

    @Override
    public long luu(long userId, String maSha256, Instant hetHan) {
        try {
            return jdbc.sql(LUU)
                    .param("userId", userId)
                    .param("maSha256", maSha256)
                    .param("hetHan", OffsetDateTime.ofInstant(hetHan, ZoneOffset.UTC))
                    .query(Long.class)
                    .single();
        } catch (DuplicateKeyException e) {
            throw IdentityException.guiLaiQuaNhanh(Duration.ZERO);
        }
    }

    @Override
    public boolean tieuThu(long id) {
        return jdbc.sql(TIEU_THU).param("id", id).update() > 0;
    }

    @Override
    public int ghiNhanThuSai(long id) {
        return jdbc.sql(GHI_NHAN_THU_SAI).param("id", id)
                .query(Integer.class).optional().orElse(0);
    }
}
