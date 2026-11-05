package dev.oj.identity.domain;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Locale;

public final class Totp {

    private static final String THUAT_TOAN = "HmacSHA1";

    public static final int BUOC_GIAY = 30;

    public static final int SO_CHU_SO = 6;

    private static final int SO_BYTE_BI_MAT = 20;

    private static final int CUA_SO_BUOC = 1;

    private static final char[] BANG_BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    private static final SecureRandom NGAU_NHIEN = new SecureRandom();

    private Totp() {
    }

    public static String sinhBiMat() {
        byte[] b = new byte[SO_BYTE_BI_MAT];
        NGAU_NHIEN.nextBytes(b);
        return maHoaBase32(b);
    }

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
            if (bangNhauHangThoiGian(sinhMa(khoa, b), ma)) {
                return b;
            }
        }
        return null;
    }

    public static String uriOtpauth(String issuer, String handle, String biMatBase32) {
        String nhan = maHoaUri(issuer) + ":" + maHoaUri(handle);
        return "otpauth://totp/" + nhan
                + "?secret=" + biMatBase32
                + "&issuer=" + maHoaUri(issuer)
                + "&algorithm=SHA1"
                + "&digits=" + SO_CHU_SO
                + "&period=" + BUOC_GIAY;
    }

    private static String sinhMa(byte[] khoa, long buoc) {
        byte[] dem = new byte[8];
        for (int i = 7; i >= 0; i--) {
            dem[i] = (byte) (buoc & 0xff);
            buoc >>>= 8;
        }
        byte[] hmac = hmac(khoa, dem);

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
