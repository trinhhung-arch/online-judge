package dev.oj.identity.domain;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * TOTP — RFC 6238. <b>Chỉ dùng JDK</b>, không thư viện nào.
 *
 * <h2>★ Vì sao HMAC-SHA1 chứ không phải SHA-256, dù SHA-1 đã yếu</h2>
 * SHA-1 yếu ở <i>chống va chạm</i> — thuộc tính mà HMAC không dựa vào. HMAC-SHA1 tới nay
 * chưa có tấn công thực tế nào, và RFC 6238 vẫn lấy nó làm mặc định.
 *
 * <p>Lý do quyết định thì thực dụng hơn: <b>Google Authenticator bỏ qua tham số
 * {@code algorithm} trong URI {@code otpauth://}</b> và luôn tính bằng SHA-1. Chọn SHA-256
 * ở phía server nghĩa là mã người dùng đọc trên điện thoại không bao giờ khớp, và triệu
 * chứng là "2FA của tôi luôn sai" — thứ không ai truy ra được nguyên nhân.
 *
 * <h2>Cửa sổ ±1 bước, không rộng hơn</h2>
 * Đồng hồ điện thoại lệch vài giây là chuyện thường, nên chấp nhận bước trước và bước sau.
 * Mỗi bước nới thêm là nhân đôi số mã hợp lệ tại một thời điểm; ±1 cho 90 giây và 3 mã,
 * đó là đánh đổi mà RFC 6238 mục 5.2 khuyến nghị.
 *
 * <h2>★ Class này KHÔNG chống phát lại — cố ý</h2>
 * Chống phát lại cần nhớ bước đã dùng, tức là cần trạng thái, tức là cần database. Ở đây là
 * {@code domain} thuần nên nó trả về <b>số bước</b> đã khớp, và người gọi
 * ({@code VerifyTotpUseCase}, {@code LoginUseCase}) có nhiệm vụ đối chiếu với
 * {@code user_two_factor.last_step}. Không làm việc ấy thì cùng một mã dùng được nhiều lần
 * trong 30 giây.
 */
public final class Totp {

    private static final String THUAT_TOAN = "HmacSHA1";

    /** RFC 6238 mục 4: bước 30 giây là mặc định, và mọi ứng dụng authenticator đều giả định thế. */
    public static final int BUOC_GIAY = 30;

    /** Sáu chữ số — cũng là con số mọi ứng dụng authenticator giả định. */
    public static final int SO_CHU_SO = 6;

    /**
     * 20 byte = 160 bit, đúng độ dài khối của SHA-1.
     *
     * <p>RFC 4226 mục 4 đòi tối thiểu 128 bit và khuyến nghị 160. Dài hơn 160 bit không làm
     * HMAC-SHA1 mạnh thêm: khoá dài hơn khối bị băm xuống trước khi dùng.
     */
    private static final int SO_BYTE_BI_MAT = 20;

    private static final int CUA_SO_BUOC = 1;

    private static final char[] BANG_BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    private static final SecureRandom NGAU_NHIEN = new SecureRandom();

    private Totp() {
    }

    /** @return bí mật mới, đã mã hoá Base32 — dạng người dùng gõ tay vào ứng dụng được */
    public static String sinhBiMat() {
        byte[] b = new byte[SO_BYTE_BI_MAT];
        NGAU_NHIEN.nextBytes(b);
        return maHoaBase32(b);
    }

    /**
     * Kiểm mã và trả về SỐ BƯỚC đã khớp, để người gọi chống phát lại.
     *
     * @param biMatBase32 bí mật đã giải mã khỏi kho
     * @param ma          sáu chữ số người dùng nhập
     * @param giayUnix    thời điểm hiện tại
     * @return số bước khớp, hoặc {@code null} nếu không mã nào trong cửa sổ khớp
     */
    public static Long kiem(String biMatBase32, String ma, long giayUnix) {
        if (ma == null || ma.length() != SO_CHU_SO) {
            return null;
        }
        for (int i = 0; i < ma.length(); i++) {
            if (ma.charAt(i) < '0' || ma.charAt(i) > '9') {
                return null;
            }
        }
        byte[] khoa = giaiMaBase32(biMatBase32);
        long buoc = Math.floorDiv(giayUnix, BUOC_GIAY);
        for (long b = buoc - CUA_SO_BUOC; b <= buoc + CUA_SO_BUOC; b++) {
            // So sánh hằng thời gian: một mã sáu chữ số dò được từng ký tự là một mã dò
            // được trong mười lượt thay vì một triệu. Cùng lý do với Jwt và InternalSecretFilter.
            if (bangNhauHangThoiGian(sinhMa(khoa, b), ma)) {
                return b;
            }
        }
        return null;
    }

    /**
     * URI cho ứng dụng authenticator quét.
     *
     * <p>{@code issuer} lặp hai lần (trong nhãn và trong tham số) là đúng chuẩn Key URI của
     * Google: bản cũ của các ứng dụng chỉ đọc nhãn, bản mới đọc tham số.
     */
    public static String uriOtpauth(String issuer, String handle, String biMatBase32) {
        String nhan = maHoaUri(issuer) + ":" + maHoaUri(handle);
        return "otpauth://totp/" + nhan
                + "?secret=" + biMatBase32
                + "&issuer=" + maHoaUri(issuer)
                + "&algorithm=SHA1"
                + "&digits=" + SO_CHU_SO
                + "&period=" + BUOC_GIAY;
    }

    // -------------------------------------------------------------------------

    private static String sinhMa(byte[] khoa, long buoc) {
        byte[] dem = new byte[8];
        for (int i = 7; i >= 0; i--) {
            dem[i] = (byte) (buoc & 0xff);
            buoc >>>= 8;
        }
        byte[] hmac = hmac(khoa, dem);

        // Dynamic truncation — RFC 4226 mục 5.3.
        int offset = hmac[hmac.length - 1] & 0x0f;
        int nhiPhan = ((hmac[offset] & 0x7f) << 24)
                | ((hmac[offset + 1] & 0xff) << 16)
                | ((hmac[offset + 2] & 0xff) << 8)
                | (hmac[offset + 3] & 0xff);
        int mod = 1;
        for (int i = 0; i < SO_CHU_SO; i++) {
            mod *= 10;
        }
        return String.format(Locale.ROOT, "%0" + SO_CHU_SO + "d", nhiPhan % mod);
    }

    private static byte[] hmac(byte[] khoa, byte[] dulieu) {
        try {
            Mac mac = Mac.getInstance(THUAT_TOAN);
            mac.init(new SecretKeySpec(khoa, THUAT_TOAN));
            return mac.doFinal(dulieu);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JVM không dùng được " + THUAT_TOAN, e);
        }
    }

    private static boolean bangNhauHangThoiGian(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int khac = 0;
        for (int i = 0; i < a.length(); i++) {
            khac |= a.charAt(i) ^ b.charAt(i);
        }
        return khac == 0;
    }

    /** Base32 RFC 4648, không đệm — JDK không có sẵn, và đây là dạng duy nhất app hiểu. */
    private static String maHoaBase32(byte[] du) {
        StringBuilder ra = new StringBuilder();
        int bo = 0;
        int soBit = 0;
        for (byte b : du) {
            bo = (bo << 8) | (b & 0xff);
            soBit += 8;
            while (soBit >= 5) {
                ra.append(BANG_BASE32[(bo >> (soBit - 5)) & 0x1f]);
                soBit -= 5;
            }
        }
        if (soBit > 0) {
            ra.append(BANG_BASE32[(bo << (5 - soBit)) & 0x1f]);
        }
        return ra.toString();
    }

    private static byte[] giaiMaBase32(String chu) {
        int bo = 0;
        int soBit = 0;
        byte[] tam = new byte[chu.length() * 5 / 8 + 1];
        int n = 0;
        for (int i = 0; i < chu.length(); i++) {
            char c = Character.toUpperCase(chu.charAt(i));
            if (c == '=') {
                continue;
            }
            int v = -1;
            for (int j = 0; j < BANG_BASE32.length; j++) {
                if (BANG_BASE32[j] == c) {
                    v = j;
                    break;
                }
            }
            if (v < 0) {
                throw new IllegalArgumentException("Bí mật TOTP không phải Base32 hợp lệ");
            }
            bo = (bo << 5) | v;
            soBit += 5;
            if (soBit >= 8) {
                tam[n++] = (byte) ((bo >> (soBit - 8)) & 0xff);
                soBit -= 8;
            }
        }
        byte[] ra = new byte[n];
        System.arraycopy(tam, 0, ra, 0, n);
        return ra;
    }

    private static String maHoaUri(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }
}
