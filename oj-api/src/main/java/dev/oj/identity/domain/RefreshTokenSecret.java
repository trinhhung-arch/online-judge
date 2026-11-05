package dev.oj.identity.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

public record RefreshTokenSecret(String giaTriTho, String sha256Hex) {

    private static final int SO_BYTE = 32;

    private static final SecureRandom NGAU_NHIEN = new SecureRandom();

    private static final Base64.Encoder MA_HOA = Base64.getUrlEncoder().withoutPadding();

    public static RefreshTokenSecret sinh() {
        byte[] bytes = new byte[SO_BYTE];
        NGAU_NHIEN.nextBytes(bytes);
        String tho = MA_HOA.encodeToString(bytes);
        return new RefreshTokenSecret(tho, bam(tho));
    }

    public static String bam(String giaTriTho) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(giaTriTho.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM không có SHA-256", e);
        }
    }

    @Override
    public String toString() {
        return "RefreshTokenSecret[sha256=" + sha256Hex.substring(0, 8) + "...]";
    }
}
