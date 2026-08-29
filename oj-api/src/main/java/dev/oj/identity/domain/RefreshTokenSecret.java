package dev.oj.identity.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Giá trị thật của một refresh token: 256 bit ngẫu nhiên, và <b>bản băm của nó</b>.
 *
 * <h2>★ Database lưu băm, không lưu token — Bước 4.4</h2>
 * Refresh token là <i>mật khẩu tương đương</i>: ai cầm nó thì đăng nhập được thành người đó
 * trong 7 ngày mà không cần biết mật khẩu. Lưu nguyên văn nghĩa là một bản sao lưu database bị
 * lộ — hay một câu {@code SELECT} sai chỗ — trao ngay quyền đăng nhập vào mọi tài khoản đang
 * mở phiên. Lưu SHA-256 thì thứ lộ ra không dùng được vào việc gì.
 *
 * <p>Vì sao SHA-256 mà không phải BCrypt như mật khẩu: mật khẩu do người nghĩ ra nên entropy
 * thấp và <i>phải</i> làm chậm để chống dò. Token này là 256 bit từ {@link SecureRandom} —
 * không có gì để dò, và nó bị tra cứu ở mỗi lần làm mới phiên nên chậm là chi phí thuần.
 *
 * <p>Không giữ trạng thái nào ngoài hai chuỗi này; {@code toString()} bị ghi đè để giá trị thô
 * không lọt vào log (bất biến #9).
 *
 * @param giaTriTho thứ trả cho người dùng. <b>Chỉ tồn tại trong đúng một response HTTP.</b>
 * @param sha256Hex 64 ký tự hex — khớp {@code CHAR(64)} của {@code refresh_tokens.token_sha256}
 */
public record RefreshTokenSecret(String giaTriTho, String sha256Hex) {

    /** 32 byte = 256 bit. Base64url không đệm ra 43 ký tự. */
    private static final int SO_BYTE = 32;

    private static final SecureRandom NGAU_NHIEN = new SecureRandom();

    private static final Base64.Encoder MA_HOA = Base64.getUrlEncoder().withoutPadding();

    public static RefreshTokenSecret sinh() {
        byte[] bytes = new byte[SO_BYTE];
        NGAU_NHIEN.nextBytes(bytes);
        String tho = MA_HOA.encodeToString(bytes);
        return new RefreshTokenSecret(tho, bam(tho));
    }

    /** Băm một giá trị người dùng trình ra, để so với cột {@code token_sha256}. */
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

    @Override
    public String toString() {
        return "RefreshTokenSecret[sha256=" + sha256Hex.substring(0, 8) + "...]";
    }
}
