package dev.oj.identity.application;

import dev.oj.identity.application.port.RefreshTokenRepository;
import dev.oj.identity.domain.RefreshTokenSecret;
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.security.CurrentUserProvider.CurrentUser;
import dev.oj.platform.security.JwtService;
import dev.oj.platform.security.Role;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

@Service
public class SessionIssuer {

    public record Session(
            String accessToken,
            String refreshToken,
            long accessTtlSeconds,
            long userId,
            String handle,
            Role role) {
    }

    private final JwtService jwt;
    private final RefreshTokenRepository refreshTokens;
    private final AppProperties properties;
    private final Clock clock;

    public SessionIssuer(JwtService jwt, RefreshTokenRepository refreshTokens,
                         AppProperties properties, Clock clock) {
        this.jwt = jwt;
        this.refreshTokens = refreshTokens;
        this.properties = properties;
        this.clock = clock;
    }

    public Session phat(long userId, String handle, Role role,
                        String userAgent, String clientIp, Long thayThe) {
        Instant bayGio = clock.instant();
        RefreshTokenSecret secret = RefreshTokenSecret.sinh();
        long tokenId = refreshTokens.luu(userId, secret.sha256Hex(), bayGio,
                bayGio.plus(properties.auth().refreshTtl()), userAgent, clientIp);

        if (thayThe != null) {
            refreshTokens.ganThayThe(thayThe, tokenId);
        }

        String accessToken = jwt.phat(new CurrentUser(userId, handle, role));
        return new Session(accessToken, secret.giaTriTho(), jwt.hanDungGiay(),
                userId, handle, role);
    }
}
