package dev.oj.identity.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Mã xác minh email: 6 chữ số, và <b>bản băm của nó</b> — FR-AUTH-09 (V13).
 *
 * <h2>★ Vì sao 6 chữ số mà không phải 32 byte như {@link RefreshTokenSecret}</h2>
 * Vì người dùng phải <b>gõ lại</b> nó. Một token 256 bit chỉ dùng được qua một đường link
 * bấm được, mà link thì kéo theo hai thứ dự án này không muốn: một trang landing riêng, và
 * một request {@code GET} làm đổi trạng thái. Mã gõ tay đi bằng {@code POST} như mọi thao
 * tác ghi khác.
 *
 * <p>Cái giá là entropy: một triệu khả năng, tức là đoán được nếu đoán thoải mái. Nên mã này
 * <b>chỉ an toàn khi ba điều kiện của V13 cùng có hiệu lực</b> — giới hạn số lần thử, hạn
 * dùng 30 phút, và mã mới huỷ mã cũ. Đọc phần đầu của {@code V13__xac_minh_email.sql} trước
 * khi đổi bất cứ điều gì ở đây: bỏ một trong ba là hạ mã này xuống mức đoán được.
 *
 * <h2>Băm SHA-256, không BCrypt — cùng lập luận với refresh token</h2>
 * BCrypt làm chậm là để chống dò một bí mật entropy thấp <i>do người nghĩ ra</i>. Mã này do
 * {@link SecureRandom} sinh và sống 30 phút; thứ chặn việc dò là bộ đếm {@code attempts},
 * không phải chi phí băm. Thêm 250ms vào một thao tác người dùng đang ngồi chờ là trả giá
 * cho một lớp bảo vệ mà lớp kia đã lo.
 *
 * <h2>Mã thô không được log — bất biến #9</h2>
 * {@code toString()} bị ghi đè. Và cụ thể hơn, có một lối tắt <b>không được đi</b>: khi tính
 * năng tắt trên máy dev, in mã ra log để tự xác minh là tiện — và là ghi thẳng một thông tin
 * xác thực vào file log. Không làm. Dev cần xác minh thì bật SMTP thật, hoặc đọc database.
 *
 * @param giaTriTho sáu chữ số, có thể bắt đầu bằng {@code 0}. <b>Chỉ tồn tại trong bộ nhớ
 *                  cho tới khi lá thư được gửi đi.</b>
 * @param sha256Hex 64 ký tự hex — khớp {@code CHAR(64)} của {@code email_verifications.code_sha256}
 */
public record MaXacMinhEmail(String giaTriTho, String sha256Hex) {

    /** Sáu chữ số: đủ ngắn để gõ lại không sai, và là thứ người dùng đã quen từ 2FA. */
    public static final int SO_CHU_SO = 6;

    private static final int TRAN = 1_000_000;

    private static final SecureRandom NGAU_NHIEN = new SecureRandom();

    /**
     * {@code nextInt(1_000_000)} rồi đệm {@code 0} ở đầu.
     *
     * <p>Đệm chứ không phải sinh trong khoảng {@code [100000, 999999]}: bỏ số có số 0 đứng
     * đầu là vứt 10% không gian mã đi, và làm thế với một mã vốn đã chỉ có một triệu khả
     * năng là tự hạ đúng cái đại lượng đang phải giữ.
     */
    public static MaXacMinhEmail sinh() {
        String tho = "%0" + SO_CHU_SO + "d";
        tho = tho.formatted(NGAU_NHIEN.nextInt(TRAN));
        return new MaXacMinhEmail(tho, bam(tho));
    }

    /** Băm mã người dùng vừa gõ, để so với cột {@code code_sha256}. */
    public static String bam(String giaTriTho) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(giaTriTho.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 là thuật toán JDK bắt buộc phải có. Tới được đây nghĩa là JVM hỏng.
            throw new IllegalStateException("JVM không có SHA-256", e);
        }
    }

    /**
     * So hai bản băm bằng {@link MessageDigest#isEqual} — thời gian không phụ thuộc nội dung.
     *
     * <p>{@code String.equals} thoát ra ở byte đầu tiên lệch nhau. Trên một endpoint mà người
     * gọi điều khiển được một vế và bấm giờ được vế kia, khoảng chênh ấy rò rỉ từng ký tự
     * một — và một bí mật dò được từng ký tự thì 6 chữ số chỉ còn là 60 lần đoán.
     *
     * <p>Thực tế mạng che phần lớn khoảng chênh đó, và {@code attempts} đã chặn việc đo lặp
     * lại. Nhưng đây là một dòng đổi lấy việc không phải tranh luận về nó.
     */
    public static boolean khop(String bamDaLuu, String maNguoiDungGo) {
        if (bamDaLuu == null || maNguoiDungGo == null) {
            return false;
        }
        return MessageDigest.isEqual(
                bamDaLuu.getBytes(StandardCharsets.UTF_8),
                bam(maNguoiDungGo).getBytes(StandardCharsets.UTF_8));
    }

    /** Chuẩn hoá thứ người dùng gõ: họ dán từ thư về kèm khoảng trắng là chuyện thường. */
    public static String chuanHoa(String ma) {
        return ma == null ? null : ma.strip();
    }

    @Override
    public String toString() {
        return "MaXacMinhEmail[sha256=" + sha256Hex.substring(0, 8) + "...]";
    }
}
