package dev.oj.identity.application.port;

import java.time.Instant;
import java.util.Optional;

/**
 * Chống dò mật khẩu — FR-AUTH-08: 5 lần sai / 1 phút / IP, khoá 15 phút.
 *
 * <h2>Vì sao là Postgres chứ không phải Redis, dù Redis đã có sẵn</h2>
 * Vì Redis ở hệ thống này là <b>cache</b>, và luật của nó ({@code oj-api/CLAUDE.md} mục 6) là
 * mọi giá trị trong Redis phải tái tạo được từ Postgres. Một lệnh khoá tài khoản biến mất khi
 * Redis restart thì không tái tạo được từ đâu cả — và "restart Redis để mở khoá" là một câu
 * mà người tấn công cũng đọc được trong mã nguồn công khai.
 *
 * <p>Chi phí chấp nhận được vì đường này <b>không nằm trên đường nóng</b>: nó chạy ở
 * {@code /auth/login}, không phải ở {@code POST /submissions}. Bước 4.7 sẽ đặt Redis lên
 * trước như một tầng đệm cho rate limit <i>chung</i>, nhưng khoá đăng nhập vẫn giữ Postgres
 * làm sự thật.
 *
 * <h2>Khoá theo IP, không theo tài khoản — cố ý</h2>
 * Khoá theo tài khoản là trao cho bất kỳ ai một nút <b>khoá tài khoản người khác</b>: gõ sai
 * năm lần vào handle của một người là họ không đăng nhập được trong 15 phút. Giữa một kỳ thi
 * thì đó là một vũ khí, không phải một biện pháp bảo vệ.
 */
public interface LoginAttemptRepository {

    /**
     * @param handleDaThu ghi lại để rà soát về sau. <b>Không bao giờ ghi mật khẩu đã thử</b> —
     *                    người ta thường gõ nhầm mật khẩu của tài khoản khác vào đây
     */
    void ghiNhan(String handleDaThu, String clientIp, boolean thanhCong);

    /** Số lần sai từ {@code moc} tới nay của một IP. */
    int demThatBaiTu(String clientIp, Instant moc);

    /** @return thời điểm hết khoá, hoặc rỗng nếu IP này không bị khoá */
    Optional<Instant> khoaToi(String clientIp);

    void khoa(String clientIp, Instant toi, String lyDo);
}
