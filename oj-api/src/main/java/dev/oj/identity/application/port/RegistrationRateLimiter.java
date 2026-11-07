package dev.oj.identity.application.port;

public interface RegistrationRateLimiter {

    void kiemVaGhiNhan(String clientIp);
}
