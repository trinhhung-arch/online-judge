package dev.oj.contract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Một cách duy nhất để băm, dùng chung bởi cả {@code oj-api} lẫn {@code oj-worker}.
 *
 * <p><b>Vì sao nó nằm trong contract chứ không phải mỗi bên tự viết:</b> hash ở đây là
 * <i>khoá</i>, không phải checksum. Cùng một chuỗi phải cho cùng một 64 ký tự ở cả hai
 * tiến trình, nếu không thì:
 * <ul>
 *   <li>khử trùng lặp {@code source_blobs} thất bại im lặng;</li>
 *   <li>cache biên dịch của worker ({@code sha256(source+lang+flags)}) không bao giờ trúng —
 *       mất luôn lợi ích lớn nhất trong contest, nơi người ta nộp lại rất nhiều
 *       ({@code nfrplan.md} 2.3 mục 3);</li>
 *   <li>cache testdata theo hash cũng miss, và không ai nhận ra vì hệ thống vẫn "chạy đúng".</li>
 * </ul>
 * Hai hiện thực khác nhau chỉ cần lệch nhau ở chữ hoa/thường là đủ gây ra cả ba.
 *
 * <p>Kết quả luôn là <b>64 ký tự hex chữ thường</b> — đúng dạng cột {@code CHAR(64)} trong DB.
 */
public final class Sha256 {

    /** Độ dài chuỗi hex của SHA-256. Bằng đúng {@code CHAR(64)} trong schema. */
    public static final int HEX_LENGTH = 64;

    private Sha256() {
    }

    /** Băm nội dung văn bản (UTF-8) — dùng cho source bài nộp và manifest testdata. */
    public static String hexOf(String value) {
        if (value == null) {
            throw new NullPointerException("value");
        }
        return hexOf(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String hexOf(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            // Không thể xảy ra: mọi JVM đều bắt buộc có SHA-256.
            throw new IllegalStateException("JVM không hỗ trợ SHA-256", e);
        }
    }

    /** Đúng dạng 64 ký tự hex chữ thường? */
    public static boolean isHex(String value) {
        if (value == null || value.length() != HEX_LENGTH) {
            return false;
        }
        for (int i = 0; i < HEX_LENGTH; i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return false;
            }
        }
        return true;
    }
}
