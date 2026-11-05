package dev.oj.identity.domain;

import dev.oj.platform.security.Role;

import java.time.Instant;
import java.util.regex.Pattern;

public record User(
        long id,
        String handle,
        String email,
        String displayName,
        Role role,
        UserStatus status,
        Short preferredLanguageId,
        Instant createdAt,
        Instant emailVerifiedAt) {

    public static final String HANDLE_REGEX = "^[A-Za-z0-9_.-]{3,32}$";

    private static final Pattern HANDLE = Pattern.compile(HANDLE_REGEX);

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    public User {
        if (id <= 0) {
            throw new IllegalArgumentException("id phải dương");
        }
        if (role == null || status == null) {
            throw new NullPointerException("role và status bắt buộc");
        }
    }

    public static String chuanHoaHandle(String handle) {
        return handle == null ? null : handle.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public static String chuanHoaEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public static void kiemTraHandle(String handle) {
        if (handle == null || !HANDLE.matcher(handle).matches()) {
            throw IdentityException.khongHopLe("identity.handle_khong_hop_le",
                    "Tên đăng nhập phải dài 3–32 ký tự và chỉ gồm chữ, số, dấu chấm, "
                            + "gạch dưới hoặc gạch ngang.");
        }
    }

    public static void kiemTraEmail(String email) {
        if (email == null || email.length() > 254 || !EMAIL.matcher(email).matches()) {
            throw IdentityException.khongHopLe("identity.email_khong_hop_le",
                    "Địa chỉ email không hợp lệ.");
        }
    }

    public boolean daXacMinhEmail() {
        return emailVerifiedAt != null;
    }

    public static void kiemTraTenHienThi(String displayName) {
        if (displayName == null || displayName.isBlank() || displayName.length() > 64) {
            throw IdentityException.khongHopLe("identity.ten_hien_thi_khong_hop_le",
                    "Tên hiển thị phải có từ 1 đến 64 ký tự.");
        }
    }

    public static String tenHienThiSauAnDanh(long userId) {
        return "[đã xoá #" + userId + "]";
    }
}
