package dev.oj.identity.application.port;

import java.time.Instant;
import java.util.Optional;

public interface LoginAttemptRepository {

    void ghiNhan(String handleDaThu, String clientIp, boolean thanhCong);

    int demThatBaiTu(String dai, Instant moc);

    Optional<Instant> khoaToi(String clientIp);

    void khoa(String dai, Instant toi, String lyDo);
}
