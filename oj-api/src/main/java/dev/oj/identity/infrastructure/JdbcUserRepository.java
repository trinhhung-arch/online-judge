package dev.oj.identity.infrastructure;

import dev.oj.identity.application.port.UserRepository;
import dev.oj.identity.domain.Credentials;
import dev.oj.identity.domain.IdentityException;
import dev.oj.identity.domain.User;
import dev.oj.identity.domain.UserStatus;
import dev.oj.platform.security.Role;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Bảng {@code users}. Chạy trên pool {@code app} — mọi lời gọi ở đây là request người dùng.
 *
 * <h2>Vì sao mọi câu tra cứu đều dùng {@code lower(...)}</h2>
 * V1 không dùng extension CITEXT (thêm extension là thêm dependency, phải hỏi người) mà dùng
 * hai index biểu thức: {@code ux_users_handle_lower} và {@code ux_users_email_lower}. Câu
 * query phải viết <b>đúng cùng biểu thức</b> thì mới trúng index. Viết {@code handle ILIKE :h}
 * là seq scan trên bảng người dùng ở mỗi lần đăng nhập.
 *
 * <p>{@code User.chuanHoaHandle} đã hạ chữ thường ở phía Java, nhưng {@code lower()} vẫn phải
 * có mặt trong câu SQL — nó là thứ chọn index, không phải thứ so sánh.
 *
 * <h2>Không có {@code SELECT *}, và {@code password_hash} chỉ xuất hiện ở hai câu</h2>
 * Đúng hai câu dưới đây đọc cột đó, cả hai đều trả về {@link Credentials}. Đây là thứ
 * {@code grep} kiểm được trong ba giây, và là lý do việc tách {@code Credentials} khỏi
 * {@link User} có giá trị thật chứ không chỉ là hình thức.
 */
@Repository
public class JdbcUserRepository implements UserRepository {

    private static final String TIM_CREDENTIALS = """
            SELECT id, handle, role, status, password_hash
              FROM users
             WHERE lower(handle) = lower(:dinhDanh)
                OR lower(email)  = lower(:dinhDanh)
            """;

    private static final String TIM_CREDENTIALS_THEO_ID = """
            SELECT id, handle, role, status, password_hash
              FROM users
             WHERE id = :id
            """;

    private static final String TIM_THEO_ID = """
            SELECT id, handle, email, display_name, role, status,
                   preferred_language_id, created_at
              FROM users
             WHERE id = :id
            """;

    private static final String DA_CO_HANDLE = """
            SELECT 1 FROM users WHERE lower(handle) = lower(:handle)
            """;

    private static final String DA_CO_EMAIL = """
            SELECT 1 FROM users WHERE lower(email) = lower(:email)
            """;

    private static final String TAO_MOI = """
            INSERT INTO users (handle, email, display_name, password_hash, role, status)
            VALUES (:handle, :email, :displayName, :passwordHash, 'USER', 'ACTIVE')
            RETURNING id
            """;

    private static final String CAP_NHAT_HO_SO = """
            UPDATE users
               SET display_name = :displayName,
                   preferred_language_id = :languageId
             WHERE id = :id
            """;

    private static final String DOI_MAT_KHAU = """
            UPDATE users SET password_hash = :passwordHash WHERE id = :id
            """;

    /**
     * Một câu duy nhất đặt cả bốn thay đổi của FR-AUTH-07.
     *
     * <p>Tách thành nhiều câu là mở ra một trạng thái trung gian trong đó
     * {@code ck_users_anonymized} bị vi phạm — status đã {@code ANONYMIZED} nhưng email chưa
     * kịp xoá — và Postgres sẽ từ chối câu đầu tiên. Ràng buộc đó buộc thao tác này phải
     * nguyên tử, và đó chính là điều nó được viết ra để làm.
     */
    private static final String AN_DANH_HOA = """
            UPDATE users
               SET status = 'ANONYMIZED',
                   email = NULL,
                   password_hash = NULL,
                   display_name = :displayName
             WHERE id = :id
            """;

    private final JdbcClient jdbc;

    public JdbcUserRepository(@Qualifier("appJdbcClient") JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Credentials> timCredentials(String handleHoacEmail) {
        return jdbc.sql(TIM_CREDENTIALS)
                .param("dinhDanh", handleHoacEmail)
                .query(CREDENTIALS)
                .optional();
    }

    @Override
    public Optional<Credentials> timCredentialsTheoId(long userId) {
        return jdbc.sql(TIM_CREDENTIALS_THEO_ID).param("id", userId).query(CREDENTIALS).optional();
    }

    @Override
    public Optional<User> timTheoId(long id) {
        return jdbc.sql(TIM_THEO_ID).param("id", id).query(NGUOI_DUNG).optional();
    }

    @Override
    public boolean daCoHandle(String handle) {
        return jdbc.sql(DA_CO_HANDLE).param("handle", handle).query(Integer.class)
                .optional().isPresent();
    }

    @Override
    public boolean daCoEmail(String email) {
        return jdbc.sql(DA_CO_EMAIL).param("email", email).query(Integer.class)
                .optional().isPresent();
    }

    @Override
    public long taoMoi(String handle, String email, String displayName, String passwordHash) {
        try {
            return jdbc.sql(TAO_MOI)
                    .param("handle", handle)
                    .param("email", email)
                    .param("displayName", displayName)
                    .param("passwordHash", passwordHash)
                    .query(Long.class)
                    .single();
        } catch (DuplicateKeyException e) {
            // Chốt thật. Tới được đây nghĩa là hai request đăng ký cùng handle chạy song song
            // và cả hai đều vượt qua bước kiểm trước đó — hiếm, nhưng có thật.
            throw IdentityException.daTonTai("Tên đăng nhập hoặc email");
        }
    }

    @Override
    public void capNhatHoSo(long userId, String displayName, Short preferredLanguageId) {
        try {
            jdbc.sql(CAP_NHAT_HO_SO)
                    .param("displayName", displayName)
                    .param("languageId", preferredLanguageId)
                    .param("id", userId)
                    .update();
        } catch (DataIntegrityViolationException e) {
            // Khoá ngoại users.preferred_language_id -> languages.id. Luật ArchUnit 3 cấm
            // identity biết bảng languages tồn tại, nên chốt duy nhất là ràng buộc này.
            throw IdentityException.khongHopLe("identity.ngon_ngu_khong_ton_tai",
                    "Ngôn ngữ được chọn không tồn tại.");
        }
    }

    @Override
    public void doiMatKhau(long userId, String passwordHash) {
        jdbc.sql(DOI_MAT_KHAU).param("passwordHash", passwordHash).param("id", userId).update();
    }

    @Override
    public void anDanhHoa(long userId, String tenHienThiMoi) {
        jdbc.sql(AN_DANH_HOA).param("displayName", tenHienThiMoi).param("id", userId).update();
    }

    private static final RowMapper<Credentials> CREDENTIALS = (rs, i) -> new Credentials(
            rs.getLong("id"),
            rs.getString("handle"),
            Role.fromCode(rs.getString("role")),
            UserStatus.fromCode(rs.getString("status")),
            rs.getString("password_hash"));

    private static final RowMapper<User> NGUOI_DUNG = (rs, i) -> new User(
            rs.getLong("id"),
            rs.getString("handle"),
            rs.getString("email"),
            rs.getString("display_name"),
            Role.fromCode(rs.getString("role")),
            UserStatus.fromCode(rs.getString("status")),
            ngonNgu(rs),
            rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant());

    /** {@code SMALLINT} nullable: {@code getShort} trả 0 cho NULL, nên phải hỏi {@code wasNull}. */
    private static Short ngonNgu(ResultSet rs) throws SQLException {
        short value = rs.getShort("preferred_language_id");
        return rs.wasNull() ? null : value;
    }
}
