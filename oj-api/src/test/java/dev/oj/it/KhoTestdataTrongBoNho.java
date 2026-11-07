package dev.oj.it;

import dev.oj.problems.application.port.TestdataStore;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;

class KhoTestdataTrongBoNho implements TestdataStore {

    @org.springframework.boot.test.context.TestConfiguration
    static class Dang {

        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        KhoTestdataTrongBoNho khoTestdataTrongBoNho() {
            return new KhoTestdataTrongBoNho();
        }
    }

    String them(String noiDungVanBan) {
        byte[] b = noiDungVanBan.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sha;
        try {
            byte[] bam = java.security.MessageDigest.getInstance("SHA-256").digest(b);
            StringBuilder sb = new StringBuilder(64);
            for (byte x : bam) {
                sb.append(Character.forDigit((x >> 4) & 0xF, 16));
                sb.append(Character.forDigit(x & 0xF, 16));
            }
            sha = sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK không có SHA-256", e);
        }
        dat(sha, b);
        return sha;
    }

    private final Map<String, byte[]> noiDung = new HashMap<>();

    void dat(String sha256, byte[] byteData) {
        noiDung.put(sha256, byteData);
    }

    @Override
    public void luu(String sha256, InputStream in, long soByte) {
        try {
            noiDung.put(sha256, in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public boolean daCo(String sha256) {
        return noiDung.containsKey(sha256);
    }

    @Override
    public InputStream doc(String sha256) {
        return new ByteArrayInputStream(noiDung.get(sha256));
    }
}
