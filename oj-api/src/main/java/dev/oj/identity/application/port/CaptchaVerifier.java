package dev.oj.identity.application.port;

public interface CaptchaVerifier {

    void kiem(String token, String clientIp);
}
