package dev.oj.identity.application.port;

public interface PasswordHasher {

    String bam(String matKhauTho);

    boolean khop(String matKhauTho, String bamDaLuu);
}
