package dev.oj.identity.domain;

import dev.oj.platform.security.Role;

public record Credentials(long userId, String handle, Role role, UserStatus status,
                          String passwordHash) {

    public boolean coTheDangNhap() {
        return status.canLogIn() && passwordHash != null;
    }

    @Override
    public String toString() {
        return "Credentials[userId=" + userId + ", role=" + role + ", status=" + status + "]";
    }
}
