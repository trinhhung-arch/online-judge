package dev.oj.identity.domain;

import java.time.Instant;

public record RefreshToken(
        long id,
        long userId,
        Instant issuedAt,
        Instant expiresAt,
        Instant revokedAt) {

    public boolean daThuHoi() {
        return revokedAt != null;
    }

    public boolean daHetHan(Instant bayGio) {
        return !bayGio.isBefore(expiresAt);
    }

    public boolean conHieuLuc(Instant bayGio) {
        return !daThuHoi() && !daHetHan(bayGio);
    }
}
