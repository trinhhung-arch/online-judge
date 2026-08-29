package dev.oj.identity.infrastructure;

import dev.oj.identity.application.port.LoginAttemptRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Bảng {@code login_attempts} và {@code login_lockouts} (V5) — FR-AUTH-08.
 *
 * <h2>{@code client_ip} có thể là {@code null}, và câu SQL phải chịu được điều đó</h2>
 * {@code HttpServletRequest.getRemoteAddr()} trả về một chuỗi hợp lệ trong mọi trường hợp
 * thật, nhưng một test gọi thẳng use-case thì không có request nào. Cột là {@code NOT NULL},
 * nên tầng trên truyền một giá trị thay thế; ở đây chỉ cần cast đúng kiểu {@code inet}.
 *
 * <p>Một IP là IPv6 dài hay một chuỗi rác từ header giả mạo đều bị chính kiểu {@code inet}
 * của Postgres từ chối — đó là một phép kiểm miễn phí mà kiểu {@code TEXT} sẽ không cho.
 *
 * <h2>Khoá ghi theo kiểu chèn-hoặc-đè</h2>
 * {@code ON CONFLICT (client_ip) DO UPDATE} — cùng một IP bị khoá lần nữa thì <b>gia hạn</b>,
 * không phải chèn thêm dòng. Nếu để nó chèn thêm thì cột khoá chính vỡ, mà nếu bỏ khoá chính
 * thì mỗi lần kiểm phải tìm dòng mới nhất trong hàng nghìn dòng của cùng một IP đang bị tấn công.
 */
@Repository
public class JdbcLoginAttemptRepository implements LoginAttemptRepository {

    private static final String GHI_NHAN = """
            INSERT INTO login_attempts (handle_tried, client_ip, succeeded)
            VALUES (:handle, CAST(:clientIp AS inet), :thanhCong)
            """;

    private static final String DEM_THAT_BAI = """
            SELECT count(*)
              FROM login_attempts
             WHERE client_ip = CAST(:clientIp AS inet)
               AND NOT succeeded
               AND attempted_at >= :moc
            """;

    private static final String KHOA_TOI = """
            SELECT locked_until
              FROM login_lockouts
             WHERE client_ip = CAST(:clientIp AS inet)
            """;

    private static final String KHOA = """
            INSERT INTO login_lockouts (client_ip, locked_until, reason)
            VALUES (CAST(:clientIp AS inet), :toi, :lyDo)
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
    public int demThatBaiTu(String clientIp, Instant moc) {
        return jdbc.sql(DEM_THAT_BAI)
                .param("clientIp", clientIp)
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
    public void khoa(String clientIp, Instant toi, String lyDo) {
        jdbc.sql(KHOA)
                .param("clientIp", clientIp)
                .param("toi", OffsetDateTime.ofInstant(toi, ZoneOffset.UTC))
                .param("lyDo", lyDo)
                .update();
    }

    /**
     * Handle đã thử là dữ liệu người ngoài gửi vào và không có giới hạn nào ở tầng HTTP. Cắt
     * ở 64 ký tự — dài hơn trần 32 ký tự của một handle hợp lệ, đủ để nhìn ra người ta đã gõ
     * gì, và không đủ để dùng bảng này làm chỗ chứa dữ liệu tuỳ ý.
     */
    private static String cat(String handle) {
        if (handle == null) {
            return "";
        }
        return handle.length() <= 64 ? handle : handle.substring(0, 64);
    }
}
