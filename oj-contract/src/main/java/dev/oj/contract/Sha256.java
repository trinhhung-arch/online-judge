package dev.oj.contract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Sha256 {

    public static final int HEX_LENGTH = 64;

    private Sha256() {
    }

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
            throw new IllegalStateException("JVM không hỗ trợ SHA-256", e);
        }
    }

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
