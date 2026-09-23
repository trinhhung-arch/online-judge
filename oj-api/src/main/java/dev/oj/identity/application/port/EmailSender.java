package dev.oj.identity.application.port;

/**
 * Cổng gửi thư ra ngoài — FR-AUTH-09. Hiện thực ở {@code identity.infrastructure}.
 *
 * <h2>★ Cổng này CÓ NGHĨA NGHIỆP VỤ, không phải {@code gui(den, tieuDe, than)}</h2>
 * Một cổng chung chung buộc use-case phải soạn tiêu đề và thân thư — tức là kéo nội dung
 * lá thư vào tầng {@code application}, nơi nó nằm cạnh logic và sẽ bị sửa cùng lúc với
 * logic. Với một phương thức mỗi loại thư thì use-case chỉ nói <i>gửi cái gì cho ai</i>, còn
 * câu chữ sống ở {@code dev.oj.identity.domain.ThuXacMinhEmail} — cùng chỗ với mọi câu
 * tiếng Việt khác mà người dùng đọc, và cùng chỗ với {@code IdentityException}.
 *
 * <p>Cái giá là mỗi loại thư mới thêm một phương thức. Đó là cái giá đúng: nó buộc người
 * thêm phải dừng lại ở interface này và trả lời "lá thư này là gì", thay vì gọi
 * {@code gui()} với một chuỗi ghép tại chỗ.
 *
 * <h2>Hỏng thì NÉM, và người gọi quyết định</h2>
 * Hiện thực ném khi không gửi được. Hai chỗ gọi xử lý hai kiểu khác nhau, cố ý:
 * {@code RegisterUserUseCase} nuốt (tài khoản đã tạo xong, hỏng thư không được làm hỏng
 * việc đăng ký), còn endpoint "gửi lại" để nó nổi lên (người dùng vừa bấm nút, họ phải
 * biết là không gửi được).
 *
 * <p>Nói cách khác: cổng này không tự quyết chuyện đó, vì nó không biết ai đang gọi.
 */
public interface EmailSender {

    /**
     * Gửi mã xác minh email.
     *
     * <p><b>Hiện thực không được log {@code ma}, và cũng không được log {@code den}.</b>
     * Mã là thông tin xác thực dùng một lần (bất biến #9); địa chỉ là dữ liệu định danh mà
     * FR-AUTH-07 hứa xoá được, và một dòng log thì không xoá được.
     *
     * @param den         địa chỉ nhận — đọc từ {@code users.email} tại thời điểm gửi
     * @param tenHienThi  để lá thư gọi đúng tên người, không phải "Dear user"
     * @param ma          sáu chữ số thô. Xem {@code MaXacMinhEmail}
     * @param soPhutConHan hạn dùng, để thư nói ra thay vì bắt người đọc đoán
     * @throws dev.oj.identity.domain.IdentityException {@code identity.khong_gui_duoc_thu}
     */
    void guiMaXacMinh(String den, String tenHienThi, String ma, long soPhutConHan);
}
