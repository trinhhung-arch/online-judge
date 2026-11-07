package dev.oj.identity.api.dto;

import dev.oj.identity.application.SessionIssuer;

public record SessionResponse(
        String accessToken,
        String refreshToken,
        long expiresIn,
        long userId,
        String handle,
        String role) {

    public static SessionResponse tu(SessionIssuer.Session session) {
        return new SessionResponse(
                session.accessToken(),
                session.refreshToken(),
                session.accessTtlSeconds(),
                session.userId(),
                session.handle(),
                session.role().name());
    }

    @Override
    public String toString() {
        return "SessionResponse[userId=" + userId + ", handle=" + handle + "]";
    }
}
