package dev.oj.identity.application.port;

public interface LoginRateLimiter {

    void kiemVaGhiNhan(long userId);
}
