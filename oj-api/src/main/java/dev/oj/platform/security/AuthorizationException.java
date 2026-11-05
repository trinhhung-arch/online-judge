package dev.oj.platform.security;

import dev.oj.platform.error.DomainException;

public class AuthorizationException extends DomainException {

    private AuthorizationException(Kind kind, String code, String publicMessage, String logMessage) {
        super(kind, code, publicMessage, logMessage);
    }

    public static AuthorizationException chuaDangNhap() {
        return new AuthorizationException(Kind.UNAUTHENTICATED, "auth.chua_dang_nhap",
                "Bạn cần đăng nhập để làm việc này.",
                "Request tới một use-case cần danh tính nhưng không mang access token");
    }

    public static AuthorizationException tokenHetHan() {
        return new AuthorizationException(Kind.UNAUTHENTICATED, "auth.token_het_han",
                "Phiên làm việc đã hết hạn.",
                "Access token quá hạn — client nên gọi /api/v1/auth/refresh");
    }

    public static AuthorizationException tokenKhongHopLe() {
        return new AuthorizationException(Kind.UNAUTHENTICATED, "auth.token_khong_hop_le",
                "Phiên làm việc không hợp lệ. Hãy đăng nhập lại.",
                "Access token hỏng hoặc sai chữ ký (không ghi giá trị token — bất biến #9)");
    }

    public static AuthorizationException thieuQuyen(Role canCo, Role dangCo) {
        return new AuthorizationException(Kind.FORBIDDEN, "auth.thieu_quyen",
                "Bạn không có quyền thực hiện thao tác này.",
                "Cần vai trò " + canCo + " nhưng người gọi là " + dangCo);
    }

    public static AuthorizationException canHaiLop() {
        return new AuthorizationException(Kind.FORBIDDEN, "auth.can_hai_lop",
                "Tài khoản quản trị phải bật xác thực hai lớp mới dùng được quyền quản trị.",
                "ADMIN chưa bật 2FA — oj.auth.require-admin-two-factor đang bật");
    }
}
