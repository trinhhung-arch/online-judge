package dev.oj.identity.application.port;

import dev.oj.identity.domain.Credentials;
import dev.oj.identity.domain.User;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UserRepository {

    Optional<Credentials> timCredentials(String handleHoacEmail);

    Optional<User> timTheoId(long id);

    Optional<Credentials> timCredentialsTheoId(long userId);

    boolean daCoHandle(String handle);

    boolean daCoEmail(String email);

    long taoMoi(String handle, String email, String displayName, String passwordHash);

    void capNhatHoSo(long userId, String displayName, Short preferredLanguageId);

    void doiMatKhau(long userId, String passwordHash);

    boolean danhDauDaXacMinhEmail(long userId);

    void anDanhHoa(long userId, String tenHienThiMoi);

    boolean doiVaiTro(long userId, String vaiTroMoi);

    boolean doiTrangThai(long userId, String trangThaiMoi);

    List<TomTatNguoiDung> danhSach(String tim, Long sauId, int gioiHan);

    record TomTatNguoiDung(long id, String handle, String displayName,
                           String vaiTro, String trangThai, Instant taoLuc) {
    }
}
