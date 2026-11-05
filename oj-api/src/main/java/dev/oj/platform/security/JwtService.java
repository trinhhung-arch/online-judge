package dev.oj.platform.security;

import dev.oj.platform.config.AppProperties;
import dev.oj.platform.security.CurrentUserProvider.CurrentUser;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;

@Component
public class JwtService {

    record Claims(long sub, String handle, String role, long exp) {
    }

    private final Jwt jwt;
    private final ObjectMapper json;
    private final java.time.Duration ttl;
    private final Clock clock;

    public JwtService(AppProperties properties, Clock clock) {
        this.jwt = new Jwt(properties.auth().jwtSecret());
        this.ttl = properties.auth().accessTtl();
        this.clock = clock;
        this.json = JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    public String phat(CurrentUser user) {
        Claims claims = new Claims(user.id(), user.handle(), user.role().name(),
                clock.instant().plus(ttl).getEpochSecond());
        return jwt.ky(json.writeValueAsBytes(claims));
    }

    CurrentUser doc(String token) {
        byte[] payload = jwt.moKhoa(token);

        Claims claims;
        try {
            claims = json.readValue(new String(payload, StandardCharsets.UTF_8), Claims.class);
        } catch (JacksonException e) {
            throw AuthorizationException.tokenKhongHopLe();
        }

        if (Instant.ofEpochSecond(claims.exp()).isBefore(clock.instant())) {
            throw AuthorizationException.tokenHetHan();
        }

        try {
            return new CurrentUser(claims.sub(), claims.handle(), Role.fromCode(claims.role()));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw AuthorizationException.tokenKhongHopLe();
        }
    }

    public long hanDungGiay() {
        return ttl.toSeconds();
    }
}
