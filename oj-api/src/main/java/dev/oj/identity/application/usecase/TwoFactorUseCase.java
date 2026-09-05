package dev.oj.identity.application.usecase;

import dev.oj.identity.application.TotpChecker;
import dev.oj.identity.application.port.PasswordHasher;
import dev.oj.identity.application.port.SecretCipher;
import dev.oj.identity.application.port.TwoFactorRepository;
import dev.oj.identity.application.port.UserRepository;
import dev.oj.identity.domain.IdentityException;
import dev.oj.identity.domain.MaDuPhong;
import dev.oj.identity.domain.Totp;
import dev.oj.platform.audit.AuditLog;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.security.RequiresRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Bật, xác nhận và tắt xác thực hai lớp — FR-AUTH-10 (V11).
 *
 * <h2>★ Ba bước, và bước giữa là bước không được bỏ</h2>
 * <ol>
 *   <li>{@link #batDau()} sinh bí mật, trả về URI để quét. 2FA <b>chưa</b> bật.</li>
 *   <li>{@link #xacNhan(String)} đòi một mã đúng rồi mới bật, và trả mã dự phòng.</li>
 *   <li>{@link #tat(String, String)} đòi cả mật khẩu lẫn mã.</li>
 * </ol>
 * Bật ngay ở bước 1 sẽ khoá người dùng ra ngoài mỗi khi họ quét hụt mã QR — và với tài
 * khoản ADMIN thì đó là khoá vĩnh viễn, vì không còn ai đủ quyền để gỡ.
 *
 * <h2>★ {@code @RequiresRole} mức USER, không phải ADMIN — cố ý</h2>
 * Nếu đòi ADMIN thì một ADMIN chưa bật 2FA sẽ bị {@code TwoFactorGate} chặn ở chính cái
 * endpoint dùng để bật 2FA. Đường vào phải nằm dưới cổng mà nó mở.
 */
@RequiresRole  // mặc định USER: ai cũng bật được 2FA cho chính mình
@Service
public class TwoFactorUseCase {

    private static final int SO_MA_DU_PHONG = 10;

    private final CurrentUserProvider currentUser;
    private final UserRepository users;
    private final TwoFactorRepository repository;
    private final SecretCipher cipher;
    private final PasswordHasher hasher;
    private final TotpChecker checker;
    private final AuditLog auditLog;
    private final java.time.Clock clock;
    private final String issuer;

    public TwoFactorUseCase(CurrentUserProvider currentUser, UserRepository users,
                            TwoFactorRepository repository,
                            SecretCipher cipher, PasswordHasher hasher, TotpChecker checker,
                            AuditLog auditLog, java.time.Clock clock) {
        this.currentUser = currentUser;
        this.users = users;
        this.repository = repository;
        this.cipher = cipher;
        this.hasher = hasher;
        this.checker = checker;
        this.auditLog = auditLog;
        this.clock = clock;
        this.issuer = "Online Judge";
    }

    /**
     * Trạng thái hiện tại, để giao diện biết vẽ nút "bật" hay khối "tắt".
     *
     * <p>Không gộp vào {@code GET /api/v1/me}: hồ sơ là dữ liệu hiển thị, còn đây là một
     * câu hỏi về bảo mật. Gộp lại thì mọi chỗ đọc hồ sơ đều mang theo câu trả lời ấy, kể
     * cả những chỗ không cần — và mỗi chỗ mang theo là một chỗ có thể lỡ log ra.
     */
    public boolean daBat() {
        return checker.dangBat(currentUser.current().id());
    }

    /**
     * Bước 1 — sinh bí mật mới, chưa bật.
     *
     * @throws IdentityException {@code 2fa_da_bat} nếu đã bật rồi. Không âm thầm ghi đè:
     *         ghi đè nghĩa là một request lạ thay được bí mật đang dùng thật và khoá chính
     *         chủ ra ngoài
     */
    @Transactional
    public BanNhap batDau() {
        var nguoi = currentUser.current();
        String biMat = Totp.sinhBiMat();
        if (!repository.luuBanNhap(nguoi.id(), cipher.maHoa(biMat))) {
            throw IdentityException.daBatHaiLop();
        }
        return new BanNhap(biMat, Totp.uriOtpauth(issuer, nguoi.handle(), biMat));
    }

    /**
     * Bước 2 — chứng minh quét được, rồi mới bật. Trả mã dự phòng ĐÚNG MỘT LẦN.
     *
     * @return mười mã dự phòng dạng thô. Server chỉ giữ bản băm, nên không có cách nào
     *         xem lại chúng — mất là phải sinh bộ mới
     */
    @Transactional
    public List<String> xacNhan(String ma) {
        var nguoi = currentUser.current();
        var tf = repository.tim(nguoi.id()).orElseThrow(IdentityException::chuaBatHaiLop);
        if (tf.enabled()) {
            throw IdentityException.daBatHaiLop();
        }
        Long buoc = Totp.kiem(cipher.giaiMa(tf.secretEnc()), ma == null ? "" : ma.trim(),
                clock.instant().getEpochSecond());
        if (buoc == null) {
            throw IdentityException.totpSai();
        }
        // Ghi luôn `buoc` làm last_step: mã vừa dùng để bật KHÔNG được dùng lại để đăng nhập.
        repository.bat(nguoi.id(), buoc);

        List<String> maDuPhong = sinhMaDuPhong();
        repository.thayMaDuPhong(nguoi.id(),
                maDuPhong.stream().map(MaDuPhong::bam).toList());
        auditLog.ghi("TWO_FACTOR_ENABLED", "user", nguoi.id(), Map.of());
        return maDuPhong;
    }

    /**
     * Bước 3 — tắt. Đòi CẢ mật khẩu lẫn mã hiện tại.
     *
     * <p>Chỉ đòi mật khẩu thì ai cướp được phiên đang mở sẽ tắt được 2FA mà không cần chạm
     * vào điện thoại — tức là 2FA bảo vệ được mọi thứ trừ chính nó.
     */
    @Transactional
    public void tat(String matKhau, String ma) {
        var nguoi = currentUser.current();
        if (!checker.dangBat(nguoi.id())) {
            throw IdentityException.chuaBatHaiLop();
        }
        // Ném saiThongTinDangNhap nếu mật khẩu sai — cùng câu chữ với đăng nhập.
        xacMinhMatKhau(nguoi.id(), matKhau);
        checker.kiem(nguoi.id(), ma);

        repository.xoa(nguoi.id());
        auditLog.ghi("TWO_FACTOR_DISABLED", "user", nguoi.id(), Map.of());
    }

    /**
     * Cùng đường mà {@code ChangePasswordUseCase} dùng: {@code timCredentialsTheoId}, chứ
     * không phải một đường đọc băm mật khẩu mới. Javadoc của {@code Credentials} liệt kê
     * đích danh những use-case được phép chạm vào nó — thêm chỗ này vào danh sách ấy là một
     * quyết định có ý thức, vì tắt 2FA đòi hỏi chứng minh danh tính đúng như đổi mật khẩu.
     */
    private void xacMinhMatKhau(long userId, String matKhau) {
        var c = users.timCredentialsTheoId(userId)
                .orElseThrow(IdentityException::khongTimThayNguoiDung);
        if (!hasher.khop(matKhau == null ? "" : matKhau, c.passwordHash())) {
            throw IdentityException.saiMatKhauCu();
        }
    }

    private static List<String> sinhMaDuPhong() {
        List<String> ra = new ArrayList<>(SO_MA_DU_PHONG);
        for (int i = 0; i < SO_MA_DU_PHONG; i++) {
            ra.add(MaDuPhong.sinh());
        }
        return ra;
    }

    /** Bí mật thô + URI để dựng mã QR ở phía client. Không bao giờ được ghi vào log. */
    public record BanNhap(String secretBase32, String otpauthUri) {
        @Override
        public String toString() {
            return "BanNhap[đã ẩn]";
        }
    }
}
