package dev.oj.identity.domain;

/**
 * Câu chữ của lá thư xác minh — FR-AUTH-09 (V13). Java thuần.
 *
 * <h2>★ Vì sao mẫu thư nằm ở {@code domain} chứ không ở {@code infrastructure}</h2>
 * Bản đầu đặt nó trong {@code SmtpEmailSender}, cạnh phần cấu hình SMTP. <b>LUẬT 5b của
 * ArchUnit từ chối</b>: không class nào trong {@code dev.oj..infrastructure..} được gọi
 * {@code String.format}, {@code formatted}, {@code concat} hay dùng {@code StringBuilder}.
 *
 * <p>Luật ấy được viết cho SQL — bất biến #5, mọi câu lệnh là hằng có named parameter — và
 * một lá thư không phải SQL. Nhưng nới luật ra cho một ngoại lệ "vô hại" là cách một luật
 * chống SQL injection chết dần: ngoại lệ thứ hai bao giờ cũng dễ hơn ngoại lệ thứ nhất.
 *
 * <p>Và khi tìm chỗ khác cho nó thì chỗ ấy hoá ra đúng hơn chỗ cũ. {@link IdentityException}
 * đã giữ toàn bộ câu chữ tiếng Việt mà người dùng đọc, ngay trong {@code domain} này. Lá thư
 * là cùng loại: nó là thứ người dùng đọc, không phải thứ SMTP cần. Cái thuộc về
 * {@code infrastructure} là <i>cách</i> gửi — máy chủ, cổng, TLS, timeout — chứ không phải
 * <i>nội dung</i> được gửi.
 *
 * <p>Nói cách khác: một luật kiến trúc vừa chỉ ra một chỗ đặt sai. Đó là việc của nó.
 */
public final class ThuXacMinhEmail {

    private ThuXacMinhEmail() {
    }

    public static String tieuDe() {
        return "Mã xác minh email — Online Judge";
    }

    /**
     * Thư thuần văn bản, <b>không HTML và không đường link</b>.
     *
     * <p>Không HTML vì thư HTML kéo theo cả một bộ khung — ảnh, CSS nội tuyến, bản dự phòng
     * văn bản, và một cái nhìn khác nhau ở mỗi ứng dụng đọc thư. Lá thư này có đúng một việc:
     * mang sáu chữ số tới nơi. Văn bản thuần làm việc đó ở mọi nơi.
     *
     * <p>Không link vì một mã gõ tay không cần link, và vì một lá thư xác minh không có link
     * là một lá thư không dạy người dùng thói quen bấm vào link trong thư tự xưng là của hệ
     * thống. Đó là thói quen mà mọi chiến dịch lừa đảo đều dựa vào.
     *
     * <p>Đoạn cuối nói rõ mã <b>vô dụng nếu không đăng nhập được</b>: người nhận một mã họ
     * không yêu cầu cần biết ngay rằng họ không phải làm gì cả.
     */
    public static String than(String tenHienThi, String ma, long soPhutConHan) {
        return """
                Chào %s,

                Mã xác minh email của bạn là:

                    %s

                Mã có hiệu lực trong %d phút. Nhập nó ở trang Hồ sơ để xác minh địa chỉ này.

                Nếu bạn không yêu cầu mã này thì bỏ qua thư — tài khoản của bạn vẫn an toàn.
                Mã chỉ dùng được bởi người đang đăng nhập vào chính tài khoản đó.

                — Online Judge
                """.formatted(tenHienThi, ma, soPhutConHan);
    }
}
