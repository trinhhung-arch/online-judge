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
import java.util.List;
import java.util.Optional;

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
                   preferred_language_id, created_at, email_verified_at
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

    private static final String DOI_VAI_TRO = """
            UPDATE users SET role = :vaiTro
             WHERE id = :id AND status <> 'ANONYMIZED'
            """;

    private static final String DOI_TRANG_THAI = """
            UPDATE users SET status = :trangThai
             WHERE id = :id AND status <> 'ANONYMIZED'
            """;

    private static final String DOI_MAT_KHAU = """
            UPDATE users SET password_hash = :passwordHash WHERE id = :id
            """;

    private static final String DANH_DAU_XAC_MINH_EMAIL = """
            UPDATE users SET email_verified_at = now()
             WHERE id = :id AND email_verified_at IS NULL AND status <> 'ANONYMIZED'
            """;

    private static final String AN_DANH_HOA = """
            UPDATE users
               SET status = 'ANONYMIZED',
                   email = NULL,
                   password_hash = NULL,
                   email_verified_at = NULL,
                   display_name = :displayName
             WHERE id = :id
            """;

    private static final String DANH_SACH = """
            SELECT id, handle, display_name, role, status, created_at
              FROM users
             WHERE (CAST(:tim AS text) IS NULL OR lower(handle) LIKE CAST(:tim AS text) ESCAPE '\\')
               AND (CAST(:sauId AS bigint) IS NULL OR id < CAST(:sauId AS bigint))
             ORDER BY id DESC
             LIMIT :gioiHan
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
            throw IdentityException.khongHopLe("identity.ngon_ngu_khong_ton_tai",
                    "Ngôn ngữ được chọn không tồn tại.");
        }
    }

    @Override
    public void doiMatKhau(long userId, String passwordHash) {
        jdbc.sql(DOI_MAT_KHAU).param("passwordHash", passwordHash).param("id", userId).update();
    }

    @Override
    public boolean danhDauDaXacMinhEmail(long userId) {
        return jdbc.sql(DANH_DAU_XAC_MINH_EMAIL).param("id", userId).update() > 0;
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
            rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant(),
            mocXacMinh(rs));

    private static Short ngonNgu(ResultSet rs) throws SQLException {
        short value = rs.getShort("preferred_language_id");
        return rs.wasNull() ? null : value;
    }

    private static java.time.Instant mocXacMinh(ResultSet rs) throws SQLException {
        var moc = rs.getObject("email_verified_at", java.time.OffsetDateTime.class);
        return moc == null ? null : moc.toInstant();
    }

    @Override
    public boolean doiVaiTro(long userId, String vaiTroMoi) {
        return jdbc.sql(DOI_VAI_TRO).param("vaiTro", vaiTroMoi).param("id", userId).update() > 0;
    }

    @Override
    public boolean doiTrangThai(long userId, String trangThaiMoi) {
        return jdbc.sql(DOI_TRANG_THAI).param("trangThai", trangThaiMoi)
                .param("id", userId).update() > 0;
    }

    @Override
    public List<TomTatNguoiDung> danhSach(String tim, Long sauId, int gioiHan) {
        return jdbc.sql(DANH_SACH)
                .param("tim", mauTim(tim))
                .param("sauId", sauId)
                .param("gioiHan", gioiHan)
                .query((rs, i) -> new TomTatNguoiDung(
                        rs.getLong("id"),
                        rs.getString("handle"),
                        rs.getString("display_name"),
                        rs.getString("role"),
                        rs.getString("status"),
                        rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant()))
                .list();
    }

    private static String mauTim(String tim) {
        if (tim == null || tim.isBlank()) {
            return null;
        }
        String thoat = tim.trim().toLowerCase(java.util.Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + thoat + "%";
    }

}
