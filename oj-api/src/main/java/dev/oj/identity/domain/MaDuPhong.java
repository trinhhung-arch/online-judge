package dev.oj.identity.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Mã dự phòng 2FA: sinh, băm, so khớp.
 *
 * <h2>★ SHA-256 chứ KHÔNG phải BCrypt, và đây là sửa một lỗi thật</h2>
 * BCrypt cố ý chậm để chống dò <b>mật khẩu người tự nghĩ ra</b> — thứ có entropy thấp và nằm
 * trong mọi bộ từ điển. Mã ở đây do {@link SecureRandom} sinh: 10 ký tự Base32 = <b>50 bit</b>,
 * không có từ điển nào chứa nó. Làm chậm phép băm không mua thêm gì.
 *
 * <p>Và nó tốn rất nhiều. Người dùng có tối đa 10 mã chưa dùng; nhập một mã SAI thì server
 * phải duyệt hết cả 10. Với BCrypt cost 12 (~250ms) đó là <b>2,5 giây CPU cho một lượt
 * đăng nhập hỏng</b> — một bộ khuếch đại gấp 10 lần đặt ngay trên đường đăng nhập, tức là
 * đúng chỗ mà {@code bcrypt-concurrency} vừa được dựng lên để bảo vệ. Với SHA-256 nó là vài
 * micro-giây.
 *
 * <p>Đây cũng là tiền lệ sẵn có của dự án: {@code refresh_tokens} lưu SHA-256 của token chứ
 * không băm chậm, vì token cũng là chuỗi ngẫu nhiên entropy cao (V5).
 *
 * <h2>So sánh hằng thời gian</h2>
 * {@link MessageDigest#isEqual} chứ không phải {@code String.equals}. Cùng lý do đã viết ở
 * {@code Jwt} và {@code InternalSecretFilter}.
 */
public final class MaDuPhong {

    /** Base32 không có ký tự dễ nhầm khi chép tay: không I, O, 0, 1. */
    private static final char[] BANG = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    /** 10 ký tự × 5 bit = 50 bit. Đủ để không dò được, đủ ngắn để chép tay. */
    private static final int DO_DAI = 10;

    private static final SecureRandom NGAU_NHIEN = new SecureRandom();

    private MaDuPhong() {
    }

    public static String sinh() {
        StringBuilder sb = new StringBuilder(DO_DAI);
        for (int i = 0; i < DO_DAI; i++) {
            sb.append(BANG[NGAU_NHIEN.nextInt(BANG.length)]);
        }
        return sb.toString();
    }

    public static String bam(String maTho) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(maTho.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM không dùng được SHA-256", e);
        }
    }

    public static boolean khop(String maTho, String bamDaLuu) {
        return MessageDigest.isEqual(
                bam(maTho).getBytes(StandardCharsets.UTF_8),
                bamDaLuu.getBytes(StandardCharsets.UTF_8));
    }
}
