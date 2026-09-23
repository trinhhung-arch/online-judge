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
 *
 * <p>"IP" ở đây là <b>một dải</b> từ 2026-09-24: IPv4 giữ nguyên, IPv6 gom về /64
 * ({@code ClientIp.khoaGioiHan}). Bước mã hai lớp thì CÓ thêm một trần theo tài khoản — ở
 * {@code TotpChecker}, và không mâu thuẫn với đoạn trên: muốn tới bước ấy phải có mật khẩu,
 * nên không ai khoá được tài khoản của người khác bằng nó.
 */
public interface LoginAttemptRepository {

    /**
     * @param handleDaThu ghi lại để rà soát về sau. <b>Không bao giờ ghi mật khẩu đã thử</b> —
     *                    người ta thường gõ nhầm mật khẩu của tài khoản khác vào đây
     */
    void ghiNhan(String handleDaThu, String clientIp, boolean thanhCong);

    /**
     * Số lần sai từ {@code moc} tới nay của mọi địa chỉ NẰM TRONG một dải.
     *
     * @param dai {@code ClientIp.khoaGioiHan(...)} — một IPv4, hoặc một dải IPv6 {@code …/64}
     */
    int demThatBaiTu(String dai, Instant moc);

    /**
     * @param clientIp địa chỉ ĐẦY ĐỦ của người gọi. Khoá nào CHỨA địa chỉ này đều tính — cả khoá
     *                 theo dải lẫn khoá theo đúng một địa chỉ ghi trước khi đổi sang dải
     * @return thời điểm hết khoá muộn nhất, hoặc rỗng nếu không bị khoá
     */
    Optional<Instant> khoaToi(String clientIp);

    /** @param dai cùng dạng với {@link #demThatBaiTu} */
    void khoa(String dai, Instant toi, String lyDo);
}
