package dev.oj.identity.domain;

public enum UserStatus {

    ACTIVE,

    DISABLED,

    ANONYMIZED;

    public boolean canLogIn() {
        return this == ACTIVE;
    }

    public static UserStatus fromCode(String code) {
        for (UserStatus s : values()) {
            if (s.name().equals(code)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Trạng thái tài khoản không hợp lệ: " + code);
    }
}
