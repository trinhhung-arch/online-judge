package dev.oj.platform.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Ký và kiểm chữ ký JWT HS256 — <b>chỉ bằng JDK</b>, không thư viện nào.
 *
 * <h2>★ Vì sao tự viết lại là lựa chọn AN TOÀN HƠN ở đây</h2>
 * "Đừng tự viết mật mã" là một lời khuyên đúng, nhưng file này không viết mật mã: nó gọi
 * {@link Mac} HMAC-SHA256 của JDK. Thứ nó tự viết là <i>định dạng</i> — ba đoạn base64url nối
 * bằng dấu chấm.
 *
 * <p>Và chính chỗ đó mới là nơi các thư viện JWT bị thủng. Hai lớp CVE thật sự cắn người dùng
 * JWT đều đến từ <b>sự linh hoạt</b> của chúng:
 *
 * <ul>
 *   <li><b>{@code alg: none}</b> — token tự khai rằng nó không cần chữ ký, và thư viện tin.</li>
 *   <li><b>Nhầm thuật toán</b> — token khai {@code HS256}, thư viện lấy <i>khoá công khai RSA</i>
 *       (thứ ai cũng biết) làm khoá HMAC, và mọi người tự ký được token.</li>
 * </ul>
 *
 * <p>Cả hai đều bắt đầu từ một câu: <i>"đọc trường {@code alg} của token rồi làm theo"</i>.
 * File này <b>không bao giờ đọc trường đó</b>. Header là {@link #HEADER_B64} — một hằng số —
 * và bước kiểm đầu tiên là so nguyên văn đoạn header của token với hằng đó. Một token
 * {@code {"alg":"none"}} bị loại trước khi bất cứ dòng nào khác chạy. Đó là miễn nhiễm
 * <b>theo cấu trúc</b>, không phải nhờ nhớ bật một tuỳ chọn.
 *
 * <h2>Thứ tự các bước trong {@link #moKhoa} là một phần của thiết kế</h2>
 * <pre>
 *   1. tách đúng 3 đoạn            — sai số đoạn thì loại
 *   2. so header với hằng số        — chặn alg=none và nhầm thuật toán
 *   3. tính lại HMAC và so hằng thời gian
 *   4. CHỈ SAU ĐÓ mới giải mã và đọc phần payload
 * </pre>
 * Bước 4 nằm sau bước 3 là có chủ đích: bộ đọc JSON không bao giờ nhìn thấy một byte nào
 * chưa được HMAC xác nhận. Mọi lỗi của bộ phân tích JSON — độ sâu lồng nhau, số quá lớn,
 * chuỗi khổng lồ — đều nằm ngoài tầm với của người không có khoá.
 *
 * <h2>So sánh chữ ký trong thời gian hằng định</h2>
 * {@link MessageDigest#isEqual} chứ không phải {@code Arrays.equals}. Cùng lý do đã viết ở
 * {@link InternalSecretFilter}: so sánh thoát sớm làm thời gian phản hồi rò rỉ độ dài tiền tố
 * đúng, và một chữ ký dò được từng byte là một chữ ký giả được.
 */
final class Jwt {

    private static final String THUAT_TOAN = "HmacSHA256";

    /**
     * Header cố định, đã mã hoá sẵn. Không sinh động, không đọc lại từ token.
     *
     * <p>Nội dung trước khi mã hoá: {@code {"alg":"HS256","typ":"JWT"}}
     */
    static final String HEADER_B64 = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9";

    /**
     * Trần độ dài token, chặn ngay ở cửa.
     *
     * <p>Không có nó thì một request mang header {@code Authorization} dài 50MB vẫn được cấp
     * phát bộ nhớ và tính HMAC trước khi bị loại. Token thật của hệ thống này dài khoảng 200
     * ký tự; 4096 đã là rộng rãi gấp hai mươi lần.
     */
    private static final int GIOI_HAN_DO_DAI = 4096;

    private static final Base64.Encoder MA_HOA = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder GIAI_MA = Base64.getUrlDecoder();

    private final SecretKeySpec khoa;

    Jwt(String secret) {
        this.khoa = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), THUAT_TOAN);
    }

    /**
     * @param payloadJson phần claim đã serialize
     * @return token dạng {@code header.payload.signature}
     */
    String ky(byte[] payloadJson) {
        String phanDauKy = HEADER_B64 + "." + MA_HOA.encodeToString(payloadJson);
        return phanDauKy + "." + MA_HOA.encodeToString(hmac(phanDauKy));
    }

    /**
     * Kiểm chữ ký và trả về phần payload thô.
     *
     * <p><b>Không</b> kiểm {@code exp} — đó là việc của {@link JwtService}, vì hạn dùng là một
     * khái niệm về thời gian chứ không phải về chữ ký, và nó cần một {@link java.time.Clock}
     * tiêm được để test.
     *
     * @throws AuthorizationException {@code auth.token_khong_hop_le} với mọi dạng hỏng
     */
    byte[] moKhoa(String token) {
        if (token == null || token.length() > GIOI_HAN_DO_DAI) {
            throw AuthorizationException.tokenKhongHopLe();
        }
        int chamMot = token.indexOf('.');
        int chamHai = token.lastIndexOf('.');
        // Đúng hai dấu chấm, và không đoạn nào rỗng.
        if (chamMot <= 0 || chamHai <= chamMot + 1 || chamHai == token.length() - 1
                || token.indexOf('.', chamMot + 1) != chamHai) {
            throw AuthorizationException.tokenKhongHopLe();
        }

        // Bước 2 — header phải là ĐÚNG hằng số của ta. Đây là chỗ alg=none chết.
        if (!HEADER_B64.equals(token.substring(0, chamMot))) {
            throw AuthorizationException.tokenKhongHopLe();
        }

        // Bước 3 — chữ ký, trước khi đọc bất cứ thứ gì.
        byte[] chuKyTrinhRa;
        try {
            chuKyTrinhRa = GIAI_MA.decode(token.substring(chamHai + 1));
        } catch (IllegalArgumentException e) {
            throw AuthorizationException.tokenKhongHopLe();
        }
        if (!MessageDigest.isEqual(hmac(token.substring(0, chamHai)), chuKyTrinhRa)) {
            throw AuthorizationException.tokenKhongHopLe();
        }

        // Bước 4 — chỉ tới đây payload mới được chạm vào.
        try {
            return GIAI_MA.decode(token.substring(chamMot + 1, chamHai));
        } catch (IllegalArgumentException e) {
            throw AuthorizationException.tokenKhongHopLe();
        }
    }

    private byte[] hmac(String duLieu) {
        try {
            Mac mac = Mac.getInstance(THUAT_TOAN);
            mac.init(khoa);
            return mac.doFinal(duLieu.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            // HmacSHA256 là thuật toán JDK bắt buộc phải có. Tới được đây nghĩa là JVM hỏng,
            // và phát ra một token không ký được còn tệ hơn là dừng lại.
            throw new IllegalStateException("JVM không dùng được " + THUAT_TOAN, e);
        }
    }
}
