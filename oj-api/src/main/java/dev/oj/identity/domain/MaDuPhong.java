package dev.oj.identity.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

public final class MaDuPhong {

    private static final char[] BANG = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

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
