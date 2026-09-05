package dev.oj.identity.infrastructure;

import dev.oj.identity.application.port.TwoFactorRepository;
import dev.oj.identity.domain.TwoFactor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Pool {@code app} — mọi câu ở đây nằm trên đường request của người dùng, không phải đường
 * verdict. Named parameter khắp nơi (bất biến #5).
 */
@Repository
public class JdbcTwoFactorRepository implements TwoFactorRepository {

    private static final String TIM = """
            SELECT user_id, secret_enc, enabled, last_step
              FROM user_two_factor
             WHERE user_id = :userId
            """;

    /**
     * {@code WHERE enabled = FALSE} trong nhánh {@code DO UPDATE} là chốt an toàn, không phải
     * tối ưu: thiếu nó thì một lời gọi "bắt đầu đăng ký" sẽ thay được bí mật của một tài
     * khoản ĐANG dùng 2FA — và khoá chính chủ ra ngoài trong khi kẻ gọi thì vào được.
     */
    private static final String LUU_BAN_NHAP = """
            INSERT INTO user_two_factor (user_id, secret_enc, enabled)
            VALUES (:userId, :secret, FALSE)
            ON CONFLICT (user_id) DO UPDATE
               SET secret_enc = EXCLUDED.secret_enc,
                   last_step  = NULL,
                   created_at = now()
             WHERE user_two_factor.enabled = FALSE
            """;

    private static final String BAT = """
            UPDATE user_two_factor
               SET enabled = TRUE, confirmed_at = now(), last_step = :buoc
             WHERE user_id = :userId AND enabled = FALSE
            """;

    private final JdbcClient jdbc;

    public JdbcTwoFactorRepository(@Qualifier("appJdbcClient") JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<TwoFactor> tim(long userId) {
        return jdbc.sql(TIM).param("userId", userId)
                .query((rs, n) -> new TwoFactor(
                        rs.getLong("user_id"),
                        rs.getString("secret_enc"),
                        rs.getBoolean("enabled"),
                        rs.getObject("last_step", Long.class)))
                .optional();
    }

    @Override
    public boolean luuBanNhap(long userId, String secretEnc) {
        return jdbc.sql(LUU_BAN_NHAP)
                .param("userId", userId).param("secret", secretEnc)
                .update() == 1;
    }

    @Override
    public void bat(long userId, long buocDaDung) {
        jdbc.sql(BAT).param("userId", userId).param("buoc", buocDaDung).update();
    }

    @Override
    public void ghiBuoc(long userId, long buoc) {
        jdbc.sql("UPDATE user_two_factor SET last_step = :buoc WHERE user_id = :userId")
                .param("userId", userId).param("buoc", buoc).update();
    }

    @Override
    public void xoa(long userId) {
        jdbc.sql("DELETE FROM user_scratch_code WHERE user_id = :userId")
                .param("userId", userId).update();
        jdbc.sql("DELETE FROM user_two_factor WHERE user_id = :userId")
                .param("userId", userId).update();
    }

    @Override
    public void thayMaDuPhong(long userId, List<String> banBam) {
        jdbc.sql("DELETE FROM user_scratch_code WHERE user_id = :userId")
                .param("userId", userId).update();
        for (String bam : banBam) {
            jdbc.sql("INSERT INTO user_scratch_code (user_id, code_hash) VALUES (:userId, :bam)")
                    .param("userId", userId).param("bam", bam).update();
        }
    }

    @Override
    public List<MaDuPhong> maDuPhongChuaDung(long userId) {
        // Không có LIMIT vì bảng này tối đa 10 dòng mỗi người theo thiết kế — bất biến #8
        // nói về danh sách trả ra API, còn đây là tra cứu nội bộ có trần cứng.
        return jdbc.sql("""
                        SELECT id, code_hash FROM user_scratch_code
                         WHERE user_id = :userId AND used_at IS NULL
                         ORDER BY id
                        """)
                .param("userId", userId)
                .query((rs, n) -> new MaDuPhong(rs.getLong("id"), rs.getString("code_hash")))
                .list();
    }

    @Override
    public void danhDauDaDung(long maDuPhongId) {
        jdbc.sql("UPDATE user_scratch_code SET used_at = now() WHERE id = :id AND used_at IS NULL")
                .param("id", maDuPhongId).update();
    }
}
