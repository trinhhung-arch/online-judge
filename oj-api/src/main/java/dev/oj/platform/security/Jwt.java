package dev.oj.platform.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;

final class Jwt {

    private static final String THUAT_TOAN = "HmacSHA256";

    static final String HEADER_B64 = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9";

    private static final int GIOI_HAN_DO_DAI = 4096;

    private static final Base64.Encoder MA_HOA = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder GIAI_MA = Base64.getUrlDecoder();

    private final SecretKeySpec khoa;

    Jwt(String secret) {
        this.khoa = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), THUAT_TOAN);
    }

    String ky(byte[] payloadJson) {
        String phanDauKy = HEADER_B64 + "." + MA_HOA.encodeToString(payloadJson);
        return phanDauKy + "." + MA_HOA.encodeToString(hmac(phanDauKy));
    }

    byte[] moKhoa(String token) {
        if (token == null || token.length() > GIOI_HAN_DO_DAI) {
            throw AuthorizationException.tokenKhongHopLe();
        }
        int chamMot = token.indexOf('.');
        int chamHai = token.lastIndexOf('.');
        if (chamMot <= 0 || chamHai <= chamMot + 1 || chamHai == token.length() - 1
                || token.indexOf('.', chamMot + 1) != chamHai) {
            throw AuthorizationException.tokenKhongHopLe();
        }

        if (!HEADER_B64.equals(token.substring(0, chamMot))) {
            throw AuthorizationException.tokenKhongHopLe();
        }

        byte[] chuKyTrinhRa;
        try {
            chuKyTrinhRa = GIAI_MA.decode(token.substring(chamHai + 1));
        } catch (IllegalArgumentException e) {
            throw AuthorizationException.tokenKhongHopLe();
        }
        if (!MessageDigest.isEqual(hmac(token.substring(0, chamHai)), chuKyTrinhRa)) {
            throw AuthorizationException.tokenKhongHopLe();
        }

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
            throw new IllegalStateException("JVM không dùng được " + THUAT_TOAN, e);
        }
    }
}
