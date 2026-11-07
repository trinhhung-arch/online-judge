package dev.oj.identity.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

public record MaXacMinhEmail(String giaTriTho, String sha256Hex) {

    public static final int SO_CHU_SO = 6;

    private static final int TRAN = 1_000_000;

    private static final SecureRandom NGAU_NHIEN = new SecureRandom();

    public static MaXacMinhEmail sinh() {
        String tho = "%0" + SO_CHU_SO + "d";
        tho = tho.formatted(NGAU_NHIEN.nextInt(TRAN));
        return new MaXacMinhEmail(tho, bam(tho));
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

    public static boolean khop(String bamDaLuu, String maNguoiDungGo) {
        if (bamDaLuu == null || maNguoiDungGo == null) {
            return false;
        }
        return MessageDigest.isEqual(
                bamDaLuu.getBytes(StandardCharsets.UTF_8),
                bam(maNguoiDungGo).getBytes(StandardCharsets.UTF_8));
    }

    public static String chuanHoa(String ma) {
        return ma == null ? null : ma.strip();
    }

    @Override
    public String toString() {
        return "MaXacMinhEmail[sha256=" + sha256Hex.substring(0, 8) + "...]";
    }
}
