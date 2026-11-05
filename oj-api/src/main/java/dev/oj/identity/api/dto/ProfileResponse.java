package dev.oj.identity.api.dto;

import dev.oj.identity.domain.User;

import java.time.Instant;

public record ProfileResponse(
        long id,
        String handle,
        String email,
        String displayName,
        String role,
        Short preferredLanguageId,
        Instant createdAt,
        boolean emailVerified) {

    public static ProfileResponse tu(User user) {
        return new ProfileResponse(
                user.id(), user.handle(), user.email(), user.displayName(),
                user.role().name(), user.preferredLanguageId(), user.createdAt(),
                user.daXacMinhEmail());
    }
}
