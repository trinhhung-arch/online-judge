package dev.oj.judging.application.port;

public interface SubmissionRateLimiter {

    void kiemTraVaGhiNhan(long userId);
}
