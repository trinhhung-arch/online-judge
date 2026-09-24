package dev.oj.identity.application.usecase;

import dev.oj.identity.application.port.EmailVerificationRepository;
import dev.oj.identity.application.port.UserRepository;
import dev.oj.identity.domain.IdentityException;
import dev.oj.identity.domain.MaXacMinhEmail;
import dev.oj.identity.domain.User;
import dev.oj.platform.audit.AuditLog;
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.security.RequiresRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Xác minh email bằng mã 6 chữ số — FR-AUTH-09 (V13).
 *
 * <h2>★ {@code noRollbackFor} là dòng quan trọng nhất file này</h2>
 * Nhánh "mã sai" làm <b>hai</b> việc rồi mới ném: tăng {@code attempts}, và khai tử mã nếu
 * chạm trần. Với một {@code @Transactional} thường, ngoại lệ cuốn trôi cả hai — và bộ đếm
 * chống dò không bao giờ nhích lên một lần nào.
 *
 * <p>Hỏng theo kiểu đó thì <b>mọi test chức năng vẫn xanh</b>: mã đúng vẫn xác minh được, mã
 * sai vẫn bị từ chối. Thứ duy nhất biến mất là trần số lần thử, tức là đúng thứ làm cho một
 * mã một-triệu-khả-năng an toàn ({@code V13__xac_minh_email.sql}). Nó sẽ chỉ lộ ra khi có
 * người ngồi dò thật.
 *
 * <p>{@code RefreshSessionUseCase} mang đúng dòng ấy vì đúng lý do ấy.
 *
 * <h2>Không kiểm cờ {@code enabled}, cố ý</h2>
 * Nếu một người đang cầm mã còn hạn từ trước lúc tính năng bị tắt, mã ấy vẫn phải dùng được —
 * nó là một lời hứa đã gửi đi rồi. Còn khi không có mã nào, câu trả lời là
 * {@code identity.ma_xac_minh_sai} với lời mời bấm gửi lại, và chính endpoint gửi lại mới
 * là nơi nói ra "máy chủ chưa bật xác minh email". Lời giải thích đến đúng chỗ người dùng
 * đang đứng.
 */
@RequiresRole
@Service
public class ConfirmEmailUseCase {

    private final CurrentUserProvider currentUser;
    private final UserRepository users;
    private final EmailVerificationRepository maXacMinh;
    private final AuditLog auditLog;
    private final int soLanThuToiDa;

    public ConfirmEmailUseCase(CurrentUserProvider currentUser, UserRepository users,
                               EmailVerificationRepository maXacMinh, AuditLog auditLog,
                               AppProperties app) {
        this.currentUser = currentUser;
        this.users = users;
        this.maXacMinh = maXacMinh;
        this.auditLog = auditLog;
        this.soLanThuToiDa = app.auth().emailVerification().maxAttempts();
    }

    /**
     * @param ma sáu chữ số người dùng gõ lại từ thư
     * @throws IdentityException {@code identity.ma_xac_minh_sai} · {@code identity.ma_xac_minh_da_chet}
     *         · {@code identity.email_da_xac_minh}
     */
    @Transactional(noRollbackFor = IdentityException.class)
    public void thucHien(String ma) {
        long userId = currentUser.current().id();
        User nguoiDung = users.timTheoId(userId)
                .orElseThrow(IdentityException::khongTimThayNguoiDung);
        if (nguoiDung.daXacMinhEmail()) {
            throw IdentityException.emailDaXacMinh();
        }

        // Rỗng thì không tốn một lượt thử: người bấm nhầm nút "Xác minh" trên một ô trống
        // không đang dò mã của ai cả, và tiêu một lượt của họ là phạt sai người.
        String daChuanHoa = MaXacMinhEmail.chuanHoa(ma);
        if (daChuanHoa == null || daChuanHoa.isEmpty()) {
            throw IdentityException.maXacMinhSai();
        }

        var dangSong = maXacMinh.timMaDangSong(userId)
                .orElseThrow(IdentityException::maXacMinhSai);

        if (!MaXacMinhEmail.khop(dangSong.sha256Hex(), daChuanHoa)) {
            throw saiHoacChet(userId, dangSong.id());
        }

        // ★ Kiểm-rồi-ghi trong MỘT câu lệnh ở repository. Hai request cùng trình một mã đúng
        // thì đúng một cái đổi được dòng; cái còn lại nhận false và rơi vào nhánh "sai".
        // Không phải để chặn gian lận — cả hai đều là chủ tài khoản — mà để không có đường
        // nào ghi `email_verified_at` hai lần từ một mã.
        if (!maXacMinh.tieuThu(dangSong.id())) {
            throw IdentityException.maXacMinhSai();
        }
        if (!users.danhDauDaXacMinhEmail(userId)) {
            // 0 dòng: một request song song vừa xác minh xong, hoặc tài khoản đã ẩn danh hoá.
            throw IdentityException.emailDaXacMinh();
        }

        // KHÔNG ghi địa chỉ email vào chi tiết: audit_log là bảng ADMIN đọc được (FR-ADM-02)
        // và chịu đúng bất biến #9. `user_id` đã đủ để tra ra ai, cho người được phép tra.
        auditLog.ghi("EMAIL_VERIFIED", "user", userId, Map.of());
    }

    /**
     * Ghi nhận một lượt gõ sai, và khai tử mã nếu nó vừa chạm trần.
     *
     * <p>Trả về ngoại lệ thay vì tự ném, để chỗ gọi đọc ra là một câu {@code throw} — người
     * đọc thấy ngay rằng nhánh ấy kết thúc ở đó, không phải đoán xem hàm này có ném hay không.
     */
    private IdentityException saiHoacChet(long userId, long maId) {
        int soLan = maXacMinh.ghiNhanThuSai(maId);
        if (soLan >= soLanThuToiDa) {
            maXacMinh.huyMaCu(userId);
            return IdentityException.maXacMinhDaChet();
        }
        return IdentityException.maXacMinhSai();
    }
}
