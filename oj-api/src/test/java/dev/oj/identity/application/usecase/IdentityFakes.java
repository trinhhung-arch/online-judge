package dev.oj.identity.application.usecase;

import dev.oj.identity.application.port.LoginAttemptRepository;
import dev.oj.identity.application.port.PasswordHasher;
import dev.oj.identity.application.port.RefreshTokenRepository;
import dev.oj.identity.application.port.UserRepository;
import dev.oj.identity.domain.Credentials;
import dev.oj.identity.domain.IdentityException;
import dev.oj.identity.domain.RefreshToken;
import dev.oj.identity.domain.User;
import dev.oj.identity.domain.UserStatus;
import dev.oj.platform.audit.AuditLog;
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.security.Role;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bản giả trong bộ nhớ cho {@code identity} — {@code CLAUDE.md} mục 6: use-case mới phải có
 * unit test với fake repository.
 *
 * <h2>{@link BamGia} băm bằng một phép nối chuỗi, và đó là lý do bộ test này chạy trong 0,2 giây</h2>
 * BCrypt cost 12 tốn ~250ms mỗi lần <b>theo thiết kế</b>. Một bộ test có ba mươi lượt đăng ký
 * và đăng nhập sẽ mất tám giây chỉ để băm, và một bộ test chậm là một bộ test người ta thôi
 * chạy trước khi commit.
 *
 * <p>Chi phí thật vẫn được đo ở đúng một chỗ: {@code IdentityHttpIT} chạy qua
 * {@code BCryptPasswordHasher} thật.
 */
final class IdentityFakes {

    private IdentityFakes() {
    }

    /** Băm rẻ tiền. Vẫn giữ đúng hợp đồng "null cũng phải xử lý được" của port. */
    static final class BamGia implements PasswordHasher {

        @Override
        public String bam(String matKhauTho) {
            return "bam:" + matKhauTho;
        }

        @Override
        public boolean khop(String matKhauTho, String bamDaLuu) {
            return bamDaLuu != null && bamDaLuu.equals(bam(matKhauTho));
        }
    }

    static final class UsersGia implements UserRepository {

        final Map<Long, User> theoId = new LinkedHashMap<>();
        final Map<Long, String> bamMatKhau = new LinkedHashMap<>();
        private final AtomicLong seq = new AtomicLong();

        long them(String handle, String email, Role role, UserStatus status, String bam) {
            long id = seq.incrementAndGet();
            theoId.put(id, new User(id, handle, email, handle, role, status, null, Instant.EPOCH));
            bamMatKhau.put(id, bam);
            return id;
        }

        @Override
        public Optional<Credentials> timCredentials(String handleHoacEmail) {
            return theoId.values().stream()
                    .filter(u -> u.handle().equalsIgnoreCase(handleHoacEmail)
                            || (u.email() != null && u.email().equalsIgnoreCase(handleHoacEmail)))
                    .findFirst()
                    .map(u -> new Credentials(u.id(), u.handle(), u.role(), u.status(),
                            bamMatKhau.get(u.id())));
        }

        @Override
        public Optional<Credentials> timCredentialsTheoId(long userId) {
            return Optional.ofNullable(theoId.get(userId))
                    .map(u -> new Credentials(u.id(), u.handle(), u.role(), u.status(),
                            bamMatKhau.get(u.id())));
        }

        @Override
        public Optional<User> timTheoId(long id) {
            return Optional.ofNullable(theoId.get(id));
        }

        @Override
        public boolean daCoHandle(String handle) {
            return theoId.values().stream().anyMatch(u -> u.handle().equalsIgnoreCase(handle));
        }

        @Override
        public boolean daCoEmail(String email) {
            return theoId.values().stream()
                    .anyMatch(u -> email.equalsIgnoreCase(u.email()));
        }

        @Override
        public long taoMoi(String handle, String email, String displayName, String passwordHash) {
            if (daCoHandle(handle)) {
                throw IdentityException.daTonTai("Tên đăng nhập");
            }
            return them(handle, email, Role.USER, UserStatus.ACTIVE, passwordHash);
        }

        @Override
        public void capNhatHoSo(long userId, String displayName, Short preferredLanguageId) {
            User u = theoId.get(userId);
            theoId.put(userId, new User(u.id(), u.handle(), u.email(), displayName, u.role(),
                    u.status(), preferredLanguageId, u.createdAt()));
        }

        @Override
        public void doiMatKhau(long userId, String passwordHash) {
            bamMatKhau.put(userId, passwordHash);
        }

        @Override
        public void anDanhHoa(long userId, String tenHienThiMoi) {
            User u = theoId.get(userId);
            theoId.put(userId, new User(u.id(), u.handle(), null, tenHienThiMoi, u.role(),
                    UserStatus.ANONYMIZED, null, u.createdAt()));
            bamMatKhau.put(userId, null);
        }

        @Override
        public boolean doiVaiTro(long userId, String vaiTroMoi) {
            User u = theoId.get(userId);
            if (u == null || u.status() == UserStatus.ANONYMIZED) {
                return false;
            }
            theoId.put(userId, new User(u.id(), u.handle(), u.email(), u.displayName(),
                    Role.valueOf(vaiTroMoi), u.status(), u.preferredLanguageId(), u.createdAt()));
            return true;
        }

        @Override
        public boolean doiTrangThai(long userId, String trangThaiMoi) {
            User u = theoId.get(userId);
            if (u == null || u.status() == UserStatus.ANONYMIZED) {
                return false;
            }
            theoId.put(userId, new User(u.id(), u.handle(), u.email(), u.displayName(),
                    u.role(), UserStatus.valueOf(trangThaiMoi), u.preferredLanguageId(),
                    u.createdAt()));
            return true;
        }
    }

    static final class TokensGia implements RefreshTokenRepository {

        final Map<String, RefreshToken> theoBam = new LinkedHashMap<>();
        final Map<Long, String> lyDoThuHoi = new LinkedHashMap<>();
        private final AtomicLong seq = new AtomicLong();

        @Override
        public long luu(long userId, String tokenSha256, Instant phatLuc, Instant hetHan,
                        String userAgent, String clientIp) {
            long id = seq.incrementAndGet();
            theoBam.put(tokenSha256, new RefreshToken(id, userId, phatLuc, hetHan, null));
            return id;
        }

        @Override
        public Optional<RefreshToken> timTheoBam(String tokenSha256) {
            return Optional.ofNullable(theoBam.get(tokenSha256));
        }

        @Override
        public void thuHoi(long tokenId, String lyDo, Long thayTheBoiId) {
            doiTrangThai(t -> t.id() == tokenId, lyDo);
        }

        @Override
        public int thuHoiTatCa(long userId, String lyDo) {
            return doiTrangThai(t -> t.userId() == userId, lyDo);
        }

        private int doiTrangThai(java.util.function.Predicate<RefreshToken> loc, String lyDo) {
            int n = 0;
            for (var muc : new ArrayList<>(theoBam.entrySet())) {
                RefreshToken t = muc.getValue();
                if (loc.test(t) && !t.daThuHoi()) {
                    theoBam.put(muc.getKey(), new RefreshToken(t.id(), t.userId(), t.issuedAt(),
                            t.expiresAt(), Instant.EPOCH));
                    lyDoThuHoi.put(t.id(), lyDo);
                    n++;
                }
            }
            return n;
        }
    }

    static final class LanThuGia implements LoginAttemptRepository {

        final List<String> ghiNhan = new ArrayList<>();
        int soLanSai;
        Instant khoaToi;

        @Override
        public void ghiNhan(String handleDaThu, String clientIp, boolean thanhCong) {
            ghiNhan.add(handleDaThu + ":" + thanhCong);
            if (!thanhCong) {
                soLanSai++;
            }
        }

        @Override
        public int demThatBaiTu(String clientIp, Instant moc) {
            return soLanSai;
        }

        @Override
        public Optional<Instant> khoaToi(String clientIp) {
            return Optional.ofNullable(khoaToi);
        }

        @Override
        public void khoa(String clientIp, Instant toi, String lyDo) {
            khoaToi = toi;
        }
    }

    static final class NhatKyGia implements AuditLog {

        final List<String> hanhDong = new ArrayList<>();
        final List<Map<String, Object>> chiTiet = new ArrayList<>();

        @Override
        public void ghi(String hanhDong, String loaiThucThe, Long idThucThe,
                        Map<String, Object> chiTiet) {
            this.hanhDong.add(hanhDong);
            this.chiTiet.add(chiTiet);
        }
    }

    static CurrentUserProvider nguoiGoi(long id, Role role) {
        return () -> new CurrentUserProvider.CurrentUser(id, "nguoi-goi", role);
    }

    static AppProperties properties() {
        return dev.oj.platform.config.AppPropertiesGia.macDinh();
    }
}
