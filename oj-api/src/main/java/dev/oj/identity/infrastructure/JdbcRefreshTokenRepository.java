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

/**
 * Bảng {@code refresh_tokens} (V5). Lưu <b>băm</b>, không lưu token.
 *
 * <h2>Vì sao thu hồi là UPDATE chứ không phải DELETE</h2>
 * Vì {@code replaced_by_id} cần dòng cũ còn tồn tại. Chuỗi xoay vòng đó là <b>cách duy nhất</b>
 * phát hiện một refresh token đã bị sao chép: khi một mắt xích đã thu hồi được trình lại,
 * nghĩa là có hai bản đang lưu hành. Xoá dòng đi thì lần trình lại ấy chỉ là
 * <i>"không tìm thấy"</i>, không phân biệt được với một token bịa ra — và cảnh báo trộm cắp
 * biến mất.
 *
 * <p>Cái giá là bảng lớn dần. Nó vài nghìn dòng mỗi tuần, và {@code ix_refresh_tokens_active}
 * là partial index chỉ phủ token còn sống, nên phần chết không làm chậm tra cứu. Dọn dẹp
 * định kỳ là việc của job vận hành (V6), không phải của đường đăng nhập.
 *
 * <h2>{@code client_ip} là {@code INET}, và pgjdbc không tự chuyển</h2>
 * {@code CAST(:clientIp AS inet)} — thiếu nó thì Postgres báo
 * <i>"column client_ip is of type inet but expression is of type character varying"</i>.
 * {@code NULL} vẫn hợp lệ qua cast.
 */
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

    /**
     * {@code AND revoked_at IS NULL} — thu hồi là idempotent, và quan trọng hơn: nó giữ nguyên
     * {@code revoked_reason} của lần thu hồi ĐẦU TIÊN. Lần đầu mới là lần mang thông tin
     * ("nghi ngờ bị đánh cắp"); ghi đè bằng một lý do sau đó là xoá mất bằng chứng.
     */
    private static final String THU_HOI = """
            UPDATE refresh_tokens
               SET revoked_at = now(), revoked_reason = :lyDo, replaced_by_id = :thayThe
             WHERE id = :id AND revoked_at IS NULL
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
    public void thuHoi(long tokenId, String lyDo, Long thayTheBoiId) {
        jdbc.sql(THU_HOI)
                .param("lyDo", lyDo)
                .param("thayThe", thayTheBoiId)
                .param("id", tokenId)
                .update();
    }

    @Override
    public int thuHoiTatCa(long userId, String lyDo) {
        return jdbc.sql(THU_HOI_TAT_CA).param("lyDo", lyDo).param("userId", userId).update();
    }

    /**
     * User-Agent do client tự đặt và không có giới hạn độ dài. Cắt ở 256 ký tự: cột là
     * {@code TEXT} nên nó nhận được cả một megabyte, và một triệu byte rác trên mỗi lần đăng
     * nhập là một cách làm phình database mà không cần quyền gì cả.
     */
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
