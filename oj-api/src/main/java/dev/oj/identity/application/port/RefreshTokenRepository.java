package dev.oj.identity.application.port;

import dev.oj.identity.domain.RefreshToken;

import java.time.Instant;
import java.util.Optional;

public interface RefreshTokenRepository {

    long luu(long userId, String tokenSha256, Instant phatLuc, Instant hetHan,
             String userAgent, String clientIp);

    Optional<RefreshToken> timTheoBam(String tokenSha256);

    boolean thuHoi(long tokenId, String lyDo, Long thayTheBoiId);

    void ganThayThe(long tokenId, long thayTheBoiId);

    int thuHoiTatCa(long userId, String lyDo);
}
