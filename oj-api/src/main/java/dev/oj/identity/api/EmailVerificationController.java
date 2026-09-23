package dev.oj.identity.api;

import dev.oj.identity.api.dto.AuthRequests;
import dev.oj.identity.application.usecase.ConfirmEmailUseCase;
import dev.oj.identity.application.usecase.SendVerificationEmailUseCase;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Xác minh email của chính mình — FR-AUTH-09 (V13).
 *
 * <h2>Nằm dưới {@code /api/v1/me}, cùng lý do với {@link TwoFactorController}</h2>
 * Cả hai endpoint đều nói về <i>người đang gọi</i>, lấy từ {@code CurrentUserProvider}.
 * Không có tham số {@code userId} nghĩa là không có gì để sửa trong URL — lỗ hổng IDOR phổ
 * biến nhất không tồn tại được ở đây, không phải vì có ai đó kiểm đúng mà vì không có đường
 * nào để kiểm sai ({@link ProfileController}).
 *
 * <p>Nó cũng là thứ thay cho một tầng rate limit theo IP: một endpoint phát thư ra ngoài mà
 * gọi được khi chưa đăng nhập thì phải có bộ đếm riêng, và phải trả lời giống hệt nhau cho
 * địa chỉ có thật lẫn không có thật. Đòi đăng nhập xoá cả hai vấn đề — xem
 * {@link SendVerificationEmailUseCase}.
 *
 * <p>Phân quyền nằm ở hai use-case, không ở đây — bất biến #11.
 *
 * <h2>Vì sao không có {@code GET} trạng thái</h2>
 * {@code TwoFactorController} có {@code GET /2fa} vì trạng thái 2FA nằm ở một bảng riêng mà
 * {@code /api/v1/me} không đọc. Ở đây thì {@code ProfileResponse.emailVerified} đã mang đúng
 * thông tin ấy trong cùng một lượt gọi giao diện vốn đã phải thực hiện. Thêm một endpoint
 * nữa là thêm một lượt đi–về cho một trường đã nằm sẵn trong response.
 */
@RestController
@RequestMapping("/api/v1/me/xac-minh-email")
public class EmailVerificationController {

    private final SendVerificationEmailUseCase gui;
    private final ConfirmEmailUseCase xacNhan;

    public EmailVerificationController(SendVerificationEmailUseCase gui,
                                       ConfirmEmailUseCase xacNhan) {
        this.gui = gui;
        this.xacNhan = xacNhan;
    }

    /**
     * Gửi (hoặc gửi lại) mã tới địa chỉ đã đăng ký.
     *
     * <p>{@code POST} chứ không {@code GET}: nó phát thư ra ngoài và ghi một dòng vào
     * database. Một thao tác mà trình duyệt được phép tự lặp lại khi tải lại trang là một
     * thao tác không được gửi thư.
     *
     * <p>Không nhận thân request — địa chỉ đọc từ tài khoản đang đăng nhập. Nhận một địa chỉ
     * từ client là biến endpoint này thành máy gửi thư tới bất kỳ đâu.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void guiMa() {
        gui.thucHien();
    }

    /** Xác nhận bằng mã 6 chữ số. Thành công thì {@code /api/v1/me} trả {@code emailVerified: true}. */
    @PostMapping("/xac-nhan")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void xacNhanMa(@RequestBody AuthRequests.XacMinhEmail body) {
        xacNhan.thucHien(body.ma());
    }
}
