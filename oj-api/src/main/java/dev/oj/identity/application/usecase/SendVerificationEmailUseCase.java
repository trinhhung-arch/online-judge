package dev.oj.identity.application.usecase;

import dev.oj.identity.application.EmailVerificationIssuer;
import dev.oj.identity.application.port.EmailVerificationRepository;
import dev.oj.identity.application.port.UserRepository;
import dev.oj.identity.domain.IdentityException;
import dev.oj.identity.domain.User;
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.config.EmailVerificationProperties;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.security.RequiresRole;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;

/**
 * Gửi lại mã xác minh cho <b>chính mình</b> — FR-AUTH-09 (V13).
 *
 * <h2>★ Cần đăng nhập, và điều đó xoá luôn ba vấn đề cùng lúc</h2>
 * Cách làm bản năng là một endpoint công khai nhận {@code handle} hoặc {@code email}. Nó kéo
 * theo cả ba thứ dưới đây, và cả ba đều biến mất khi endpoint đòi đăng nhập:
 *
 * <ol>
 *   <li><b>Dò tài khoản.</b> Một endpoint công khai nhận email phải trả lời giống hệt nhau
 *       cho địa chỉ có thật và không có thật — nếu không thì nó là máy dò danh sách người
 *       dùng, đúng thứ {@code IdentityException.saiThongTinDangNhap} được viết ra để chặn.</li>
 *   <li><b>Dội thư vào người khác.</b> Ai cũng gọi được thì ai cũng bắt hệ thống gửi thư tới
 *       địa chỉ của người khác được.</li>
 *   <li><b>Một tầng rate limit nữa.</b> Một cửa phát thư ra ngoài mà không cần danh tính thì
 *       phải có bộ đếm theo IP riêng, với Redis riêng, hỏng theo kiểu riêng.</li>
 * </ol>
 *
 * <p>Và nó không mất gì: người vừa đăng ký thì giao diện đăng nhập luôn cho họ, còn người
 * quay lại sau vẫn đăng nhập được bình thường — xác minh ở mức mềm <b>không</b> chặn đăng
 * nhập (ADR 016). Nếu một ngày nào đó nó chặn, endpoint này phải được nghĩ lại từ đầu, vì
 * lúc ấy "đăng nhập rồi mới xin được mã" trở thành một vòng lặp không lối ra.
 *
 * <h2>Khoảng chờ bảo vệ hộp thư của người dùng, không bảo vệ hệ thống</h2>
 * Người đã đăng nhập vẫn bấm được nút này bao nhiêu lần tuỳ thích, và mỗi lần là một lá thư
 * thật gửi tới địa chỉ thật. Trần API chung (100 lượt/phút/người) chặn được việc làm nghẽn
 * server, nhưng 100 lá thư trong một phút vào hộp thư của chính mình thì nó không coi là vấn
 * đề. {@code resend-cooldown} coi là.
 */
@RequiresRole
@Service
public class SendVerificationEmailUseCase {

    private final CurrentUserProvider currentUser;
    private final UserRepository users;
    private final EmailVerificationRepository maXacMinh;
    private final EmailVerificationIssuer issuer;
    private final EmailVerificationProperties properties;
    private final Clock clock;

    public SendVerificationEmailUseCase(CurrentUserProvider currentUser, UserRepository users,
                                        EmailVerificationRepository maXacMinh,
                                        EmailVerificationIssuer issuer,
                                        AppProperties app, Clock clock) {
        this.currentUser = currentUser;
        this.users = users;
        this.maXacMinh = maXacMinh;
        this.issuer = issuer;
        this.properties = app.auth().emailVerification();
        this.clock = clock;
    }

    /**
     * @throws IdentityException {@code identity.xac_minh_email_tat} nếu máy chủ chưa bật ·
     *         {@code identity.email_da_xac_minh} nếu đã xong ·
     *         {@code identity.gui_lai_qua_nhanh} nếu chưa hết khoảng chờ ·
     *         {@code identity.khong_gui_duoc_thu} nếu SMTP từ chối
     */
    public void thucHien() {
        // ★ Nói thẳng là tính năng đang tắt, thay vì trả 204 rồi không gửi gì. Một người bấm
        // "gửi mã" xong ngồi chờ một lá thư không bao giờ tới là kiểu hỏng tệ nhất: không có
        // gì trên màn hình sai, và không có gì nói vì sao.
        //
        // Đây là câu `if` DUY NHẤT trong tầng use-case đọc cờ `enabled`. Đường đăng ký không
        // có câu này — ở đó `EmailVerificationIssuer` tự im lặng, vì người đăng ký không bấm
        // nút nào và không chờ lá thư nào cả.
        if (!properties.enabled()) {
            throw IdentityException.xacMinhEmailTat();
        }

        User nguoiDung = users.timTheoId(currentUser.current().id())
                .orElseThrow(IdentityException::khongTimThayNguoiDung);

        if (nguoiDung.daXacMinhEmail()) {
            throw IdentityException.emailDaXacMinh();
        }
        if (nguoiDung.email() == null) {
            // Tài khoản đã ẩn danh hoá (FR-AUTH-07) — không còn địa chỉ để gửi tới. Về lý
            // thuyết không tới được đây vì phiên đã bị thu hồi, nhưng một access token còn
            // hạn tối đa 15 phút thì vẫn dùng được, và `to = null` là một NPE ở tầng SMTP.
            throw IdentityException.khongTimThayNguoiDung();
        }

        khoangCho(nguoiDung.id());

        issuer.phatVaGui(nguoiDung.id(), nguoiDung.email(), nguoiDung.displayName());
    }

    /**
     * Khoảng chờ tính từ mã đang sống gần nhất.
     *
     * <p>Lưu ý một hệ quả cố ý: nếu lượt trước ghi được mã nhưng SMTP từ chối, khoảng chờ
     * <b>vẫn</b> áp. Nhìn thì thấy phiền — người dùng chưa nhận được gì mà đã bị bắt chờ —
     * nhưng đó đúng là điều nên làm: lúc SMTP đang hỏng, cho bấm lại không giới hạn chỉ là
     * dội thêm vào một máy chủ đang không trả lời.
     */
    private void khoangCho(long userId) {
        var dangSong = maXacMinh.timMaDangSong(userId);
        if (dangSong.isEmpty()) {
            return;
        }
        Duration daQua = Duration.between(dangSong.get().taoLuc(), clock.instant());
        if (daQua.compareTo(properties.resendCooldown()) < 0) {
            throw IdentityException.guiLaiQuaNhanh(properties.resendCooldown().minus(daQua));
        }
    }
}
