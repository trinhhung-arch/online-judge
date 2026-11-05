package dev.oj.identity.application.usecase;

import dev.oj.identity.application.port.EmailSender;
import dev.oj.identity.application.port.EmailVerificationRepository;
import dev.oj.identity.application.port.LoginAttemptRepository;
import dev.oj.identity.application.port.PasswordHasher;
import dev.oj.identity.application.port.RegistrationRateLimiter;
import dev.oj.identity.application.port.SecretCipher;
import dev.oj.identity.application.port.TwoFactorRepository;
import dev.oj.identity.application.port.RefreshTokenRepository;
import dev.oj.identity.application.port.UserRepository;
import dev.oj.identity.domain.Credentials;
import dev.oj.identity.domain.IdentityException;
import dev.oj.identity.domain.RefreshToken;
import dev.oj.identity.domain.TwoFactor;
import dev.oj.identity.domain.User;
import dev.oj.identity.domain.UserStatus;
import dev.oj.platform.audit.AuditLog;
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.security.Role;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

final class IdentityFakes {

    private IdentityFakes() {
    }

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
            theoId.put(id, new User(id, handle, email, handle, role, status, null,
                    Instant.EPOCH, null));
            bamMatKhau.put(id, bam);
            return id;
        }

        @Override
        public List<TomTatNguoiDung> danhSach(String tim, Long sauId, int gioiHan) {
            return theoId.values().stream()
                    .filter(u -> tim == null || tim.isBlank()
                            || u.handle().toLowerCase(java.util.Locale.ROOT)
                                    .contains(tim.trim().toLowerCase(java.util.Locale.ROOT)))
                    .filter(u -> sauId == null || u.id() < sauId)
                    .sorted((a, b) -> Long.compare(b.id(), a.id()))
                    .limit(gioiHan)
                    .map(u -> new TomTatNguoiDung(u.id(), u.handle(), u.displayName(),
                            u.role().name(), u.status().name(), u.createdAt()))
                    .toList();
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
                    u.status(), preferredLanguageId, u.createdAt(), u.emailVerifiedAt()));
        }

        @Override
        public void doiMatKhau(long userId, String passwordHash) {
            bamMatKhau.put(userId, passwordHash);
        }

        @Override
        public boolean danhDauDaXacMinhEmail(long userId) {
            User u = theoId.get(userId);
            if (u == null || u.daXacMinhEmail() || u.status() == UserStatus.ANONYMIZED) {
                return false;
            }
            theoId.put(userId, new User(u.id(), u.handle(), u.email(), u.displayName(), u.role(),
                    u.status(), u.preferredLanguageId(), u.createdAt(), Instant.EPOCH));
            return true;
        }

        @Override
        public void anDanhHoa(long userId, String tenHienThiMoi) {
            User u = theoId.get(userId);
            theoId.put(userId, new User(u.id(), u.handle(), null, tenHienThiMoi, u.role(),
                    UserStatus.ANONYMIZED, null, u.createdAt(), null));
            bamMatKhau.put(userId, null);
        }

        @Override
        public boolean doiVaiTro(long userId, String vaiTroMoi) {
            User u = theoId.get(userId);
            if (u == null || u.status() == UserStatus.ANONYMIZED) {
                return false;
            }
            theoId.put(userId, new User(u.id(), u.handle(), u.email(), u.displayName(),
                    Role.valueOf(vaiTroMoi), u.status(), u.preferredLanguageId(), u.createdAt(),
                    u.emailVerifiedAt()));
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
                    u.createdAt(), u.emailVerifiedAt()));
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

        boolean requestKhacThuHoiTruoc;

        @Override
        public boolean thuHoi(long tokenId, String lyDo, Long thayTheBoiId) {
            if (requestKhacThuHoiTruoc) {
                doiTrangThai(t -> t.id() == tokenId, "request khác");
            }
            return doiTrangThai(t -> t.id() == tokenId, lyDo) == 1;
        }

        @Override
        public void ganThayThe(long tokenId, long thayTheBoiId) {
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

    static final class ChanDangKyGia implements RegistrationRateLimiter {

        final Map<String, Integer> dem = new HashMap<>();
        int toiDa = 10;

        @Override
        public void kiemVaGhiNhan(String clientIp) {
            int moi = dem.merge(clientIp, 1, Integer::sum);
            if (moi > toiDa) {
                throw IdentityException.quaNhieuDangKy(java.time.Duration.ofHours(1));
            }
        }
    }

    static final class MaHoaGia implements SecretCipher {
        @Override
        public String maHoa(String roThô) {
            return "enc:" + roThô;
        }

        @Override
        public String giaiMa(String daMaHoa) {
            return daMaHoa.substring("enc:".length());
        }
    }

    static final class HaiLopGia implements TwoFactorRepository {

        final Map<Long, TwoFactor> theoUser = new LinkedHashMap<>();
        final Map<Long, List<MaDuPhong>> maDuPhong = new LinkedHashMap<>();
        private long idKe = 1;

        @Override
        public Optional<TwoFactor> tim(long userId) {
            return Optional.ofNullable(theoUser.get(userId));
        }

        @Override
        public boolean luuBanNhap(long userId, String secretEnc) {
            TwoFactor cu = theoUser.get(userId);
            if (cu != null && cu.enabled()) {
                return false;
            }
            theoUser.put(userId, new TwoFactor(userId, secretEnc, false, null));
            return true;
        }

        @Override
        public void bat(long userId, long buocDaDung) {
            TwoFactor cu = theoUser.get(userId);
            theoUser.put(userId, new TwoFactor(userId, cu.secretEnc(), true, buocDaDung));
        }

        boolean requestKhacDungMaTruoc;

        @Override
        public boolean ghiBuoc(long userId, long buoc) {
            TwoFactor cu = theoUser.get(userId);
            if (requestKhacDungMaTruoc || (cu.lastStep() != null && cu.lastStep() >= buoc)) {
                return false;
            }
            theoUser.put(userId, new TwoFactor(userId, cu.secretEnc(), cu.enabled(), buoc));
            return true;
        }

        @Override
        public void xoa(long userId) {
            theoUser.remove(userId);
            maDuPhong.remove(userId);
        }

        @Override
        public void thayMaDuPhong(long userId, List<String> banBam) {
            List<MaDuPhong> ds = new ArrayList<>();
            for (String b : banBam) {
                ds.add(new MaDuPhong(idKe++, b));
            }
            maDuPhong.put(userId, ds);
        }

        @Override
        public List<MaDuPhong> maDuPhongChuaDung(long userId) {
            return new ArrayList<>(maDuPhong.getOrDefault(userId, List.of()));
        }

        final Map<Long, Integer> maSai = new LinkedHashMap<>();
        final Map<Long, Instant> khoaToi = new LinkedHashMap<>();

        @Override
        public Optional<Instant> khoaHaiLopToi(long userId) {
            return Optional.ofNullable(khoaToi.get(userId));
        }

        @Override
        public boolean ghiMaSai(long userId, int nguong, Instant toi) {
            int n = maSai.getOrDefault(userId, 0) + 1;
            if (n >= nguong) {
                maSai.put(userId, 0);
                khoaToi.put(userId, toi);
                return true;
            }
            maSai.put(userId, n);
            return false;
        }

        @Override
        public void xoaDemMaSai(long userId) {
            maSai.put(userId, 0);
        }

        @Override
        public boolean danhDauDaDung(long maDuPhongId) {
            boolean daXoa = false;
            for (List<MaDuPhong> ds : maDuPhong.values()) {
                daXoa |= ds.removeIf(m -> m.id() == maDuPhongId);
            }
            return daXoa && !requestKhacDungMaTruoc;
        }
    }

    static final class XacMinhEmailGia implements EmailVerificationRepository {

        record Dong(long id, long userId, String sha256Hex, Instant taoLuc, Instant hetHan,
                    int soLanThu, boolean daTieu) {
        }

        final List<Dong> dong = new ArrayList<>();
        private final AtomicLong seq = new AtomicLong();

        Instant bayGio = Instant.EPOCH;

        boolean requestKhacTieuTruoc;

        boolean requestKhacChenTruoc;

        @Override
        public Optional<MaDangSong> timMaDangSong(long userId) {
            return dong.stream()
                    .filter(d -> d.userId() == userId && !d.daTieu() && d.hetHan().isAfter(bayGio))
                    .findFirst()
                    .map(d -> new MaDangSong(d.id(), d.sha256Hex(), d.taoLuc(), d.soLanThu()));
        }

        @Override
        public int huyMaCu(long userId) {
            int n = 0;
            for (int i = 0; i < dong.size(); i++) {
                Dong d = dong.get(i);
                if (d.userId() == userId && !d.daTieu()) {
                    dong.set(i, danhDauTieu(d));
                    n++;
                }
            }
            return n;
        }

        @Override
        public long luu(long userId, String maSha256, Instant hetHan) {
            if (requestKhacChenTruoc) {
                throw IdentityException.guiLaiQuaNhanh(java.time.Duration.ZERO);
            }
            if (timMaDangSong(userId).isPresent()) {
                throw new IllegalStateException(
                        "ux_email_verifications_song: userId=" + userId + " đã có một mã sống. "
                                + "Phải gọi huyMaCu() trước — database thật sẽ từ chối câu chèn này");
            }
            long id = seq.incrementAndGet();
            dong.add(new Dong(id, userId, maSha256, bayGio, hetHan, 0, false));
            return id;
        }

        @Override
        public boolean tieuThu(long id) {
            for (int i = 0; i < dong.size(); i++) {
                Dong d = dong.get(i);
                if (d.id() != id) {
                    continue;
                }
                if (requestKhacTieuTruoc || d.daTieu() || !d.hetHan().isAfter(bayGio)) {
                    return false;
                }
                dong.set(i, danhDauTieu(d));
                return true;
            }
            return false;
        }

        @Override
        public int ghiNhanThuSai(long id) {
            for (int i = 0; i < dong.size(); i++) {
                Dong d = dong.get(i);
                if (d.id() == id && !d.daTieu()) {
                    dong.set(i, new Dong(d.id(), d.userId(), d.sha256Hex(), d.taoLuc(),
                            d.hetHan(), d.soLanThu() + 1, false));
                    return d.soLanThu() + 1;
                }
            }
            return 0;
        }

        private static Dong danhDauTieu(Dong d) {
            return new Dong(d.id(), d.userId(), d.sha256Hex(), d.taoLuc(), d.hetHan(),
                    d.soLanThu(), true);
        }
    }

    static final class ThuGia implements EmailSender {

        record LaThu(String den, String tenHienThi, String ma, long soPhutConHan) {
        }

        final List<LaThu> daGui = new ArrayList<>();

        boolean hong;

        @Override
        public void guiMaXacMinh(String den, String tenHienThi, String ma, long soPhutConHan) {
            if (hong) {
                throw IdentityException.khongGuiDuocThu();
            }
            daGui.add(new LaThu(den, tenHienThi, ma, soPhutConHan));
        }

        String maMoiNhat() {
            return daGui.getLast().ma();
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
