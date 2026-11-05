package dev.oj.platform.security;

public enum Role {

    USER,

    SETTER,

    ADMIN;

    public boolean atLeast(Role other) {
        return this.ordinal() >= other.ordinal();
    }

    public boolean isAdmin() {
        return this == ADMIN;
    }

    public boolean canAuthorProblems() {
        return atLeast(SETTER);
    }

    public static Role fromCode(String code) {
        for (Role r : values()) {
            if (r.name().equals(code)) {
                return r;
            }
        }
        throw new IllegalArgumentException("Vai trò không hợp lệ: " + code);
    }
}
