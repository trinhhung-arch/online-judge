package dev.oj.identity.domain;

import java.nio.charset.StandardCharsets;

public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;

    public static final int MAX_BYTES = 72;

    private PasswordPolicy() {
    }

    public static void kiemTra(String matKhau) {
        if (matKhau == null || matKhau.length() < MIN_LENGTH) {
            throw IdentityException.khongHopLe("identity.mat_khau_qua_ngan",
                    "Mật khẩu phải có ít nhất " + MIN_LENGTH + " ký tự.");
        }
        int soByte = matKhau.getBytes(StandardCharsets.UTF_8).length;
        if (soByte > MAX_BYTES) {
            throw IdentityException.khongHopLe("identity.mat_khau_qua_dai",
                    "Mật khẩu quá dài (tối đa " + MAX_BYTES + " byte, khoảng "
                            + MAX_BYTES + " ký tự tiếng Anh hoặc " + (MAX_BYTES / 3)
                            + " ký tự tiếng Việt có dấu).");
        }
    }
}
