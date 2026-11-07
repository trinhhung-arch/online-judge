package dev.oj.identity.domain;

import dev.oj.platform.error.DomainException;

import java.time.Duration;

public class IdentityException extends DomainException {

    private IdentityException(Kind kind, String code, String publicMessage) {
        super(kind, code, publicMessage);
    }

    private IdentityException(Kind kind, String code, String publicMessage, String logMessage) {
        super(kind, code, publicMessage, logMessage);
    }

    private IdentityException(Kind kind, String code, String publicMessage, String logMessage,
                              Duration retryAfter) {
        super(kind, code, publicMessage, logMessage, retryAfter, null);
    }

    public static IdentityException khongHopLe(String code, String publicMessage) {
        return new IdentityException(Kind.INVALID, code, publicMessage);
    }

    public static IdentityException daTonTai(String truong) {
        return new IdentityException(Kind.CONFLICT, "identity.da_ton_tai",
                truong + " này đã có người dùng. Hãy chọn " + truong + " khác.",
                "Đăng ký trùng " + truong);
    }

    public static IdentityException saiThongTinDangNhap() {
        return new IdentityException(Kind.UNAUTHENTICATED, "identity.sai_thong_tin",
                "Tên đăng nhập hoặc mật khẩu không đúng.",
                "Đăng nhập thất bại (không ghi handle đã thử vào đây — bất biến #9)");
    }

    public static IdentityException haiLopTamKhoa(Duration conLai) {
        return new IdentityException(Kind.RATE_LIMITED, "identity.hai_lop_tam_khoa",
                "Nhập sai mã xác thực quá nhiều lần. Thử lại sau "
                        + Math.max(1, conLai.toMinutes()) + " phút.",
                "Bước mã hai lớp của tài khoản bị khoá tạm (V15)", conLai);
    }

    public static IdentityException daKhoaTam(Duration conLai) {
        return new IdentityException(Kind.RATE_LIMITED, "identity.khoa_tam",
                "Sai quá nhiều lần. Thử lại sau " + conLai.toMinutes() + " phút.",
                "IP bị khoá đăng nhập tạm thời", conLai);
    }

    public static IdentityException quaNhieuDangKy(Duration conLai) {
        return new IdentityException(Kind.RATE_LIMITED, "identity.dang_ky_qua_nhieu",
                "Đã tạo quá nhiều tài khoản từ địa chỉ này. Thử lại sau "
                        + Math.max(1, conLai.toMinutes()) + " phút.",
                "IP chạm giới hạn đăng ký", conLai);
    }

    public static IdentityException heThongBan(Duration conLai) {
        return new IdentityException(Kind.RATE_LIMITED, "identity.he_thong_ban",
                "Hệ thống đang bận xử lý đăng nhập. Thử lại sau vài giây.",
                "Chạm trần oj.auth.bcrypt-concurrency", conLai);
    }

    public static IdentityException captchaKhongHopLe() {
        return new IdentityException(Kind.INVALID, "identity.captcha_khong_hop_le",
                "Xác minh chống bot không thành công. Hãy thử lại.",
                "Turnstile từ chối hoặc không hỏi lại được");
    }

    public static IdentityException dangNhapQuaNhanh(Duration conLai) {
        return new IdentityException(Kind.RATE_LIMITED, "identity.dang_nhap_qua_nhanh",
                "Bạn vừa đăng nhập xong. Thử lại sau vài giây.",
                "Chạm oj.auth.login-min-interval", conLai);
    }

    public static IdentityException canTotp() {
        return new IdentityException(Kind.UNAUTHENTICATED, "identity.can_totp",
                "Tài khoản này bật xác thực hai lớp. Nhập mã 6 chữ số từ ứng dụng.",
                "Đăng nhập đúng mật khẩu, chờ mã TOTP");
    }

    public static IdentityException totpSai() {
        return new IdentityException(Kind.UNAUTHENTICATED, "identity.totp_sai",
                "Mã xác thực không đúng hoặc đã được dùng.",
                "Mã TOTP không khớp (không ghi mã đã thử vào đây — bất biến #9)");
    }

    public static IdentityException daBatHaiLop() {
        return new IdentityException(Kind.CONFLICT, "identity.2fa_da_bat",
                "Tài khoản đã bật xác thực hai lớp. Tắt trước rồi bật lại nếu muốn đổi thiết bị.",
                "Gọi bắt đầu đăng ký 2FA khi đã bật");
    }

    public static IdentityException chuaBatHaiLop() {
        return new IdentityException(Kind.INVALID, "identity.2fa_chua_bat",
                "Tài khoản chưa bắt đầu đăng ký xác thực hai lớp.",
                "Gọi xác nhận/tắt 2FA khi chưa có bản nháp");
    }

    public static IdentityException adminPhaiBatHaiLop() {
        return new IdentityException(Kind.FORBIDDEN, "identity.admin_can_2fa",
                "Tài khoản quản trị phải bật xác thực hai lớp mới dùng được quyền quản trị.",
                "ADMIN chưa bật 2FA — oj.auth.require-admin-two-factor đang bật");
    }

    public static IdentityException maXacMinhSai() {
        return new IdentityException(Kind.INVALID, "identity.ma_xac_minh_sai",
                "Mã xác minh không đúng hoặc đã hết hạn. Hãy bấm gửi lại để nhận mã mới.",
                "Mã xác minh email không khớp (không ghi mã đã thử vào đây — bất biến #9)");
    }

    public static IdentityException maXacMinhDaChet() {
        return new IdentityException(Kind.INVALID, "identity.ma_xac_minh_da_chet",
                "Đã nhập sai quá nhiều lần nên mã này không còn dùng được. "
                        + "Hãy bấm gửi lại để nhận mã mới.",
                "Mã xác minh email chạm trần max-attempts và đã bị khai tử");
    }

    public static IdentityException guiLaiQuaNhanh(Duration conLai) {
        return new IdentityException(Kind.RATE_LIMITED, "identity.gui_lai_qua_nhanh",
                "Vừa gửi một mã rồi. Kiểm tra hộp thư, hoặc thử lại sau "
                        + Math.max(1, conLai.toSeconds()) + " giây.",
                "Chạm oj.auth.email-verification.resend-cooldown", conLai);
    }

    public static IdentityException emailDaXacMinh() {
        return new IdentityException(Kind.CONFLICT, "identity.email_da_xac_minh",
                "Email của tài khoản này đã được xác minh.",
                "Gọi gửi/xác minh khi email_verified_at đã có giá trị");
    }

    public static IdentityException xacMinhEmailTat() {
        return new IdentityException(Kind.CONFLICT, "identity.xac_minh_email_tat",
                "Máy chủ này chưa bật xác minh email.",
                "oj.auth.email-verification.enabled = false");
    }

    public static IdentityException khongGuiDuocThu() {
        return new IdentityException(Kind.UNAVAILABLE, "identity.khong_gui_duoc_thu",
                "Không gửi được thư xác minh lúc này. Hãy thử lại sau ít phút.",
                "SMTP từ chối hoặc không kết nối được");
    }

    public static IdentityException phienKhongHopLe() {
        return new IdentityException(Kind.UNAUTHENTICATED, "identity.phien_khong_hop_le",
                "Phiên đăng nhập đã hết hạn. Hãy đăng nhập lại.");
    }

    public static IdentityException phienBiDungLai() {
        return new IdentityException(Kind.UNAUTHENTICATED, "identity.phien_bi_dung_lai",
                "Phiên đăng nhập không còn hiệu lực. Hãy đăng nhập lại.",
                "Refresh token đã thu hồi được trình lại — nghi ngờ token bị đánh cắp, "
                        + "đã thu hồi toàn bộ phiên của tài khoản");
    }

    public static IdentityException khongTimThayNguoiDung() {
        return new IdentityException(Kind.NOT_FOUND, "identity.khong_tim_thay",
                "Không tìm thấy tài khoản.");
    }

    public static IdentityException saiMatKhauCu() {
        return new IdentityException(Kind.INVALID, "identity.sai_mat_khau_cu",
                "Mật khẩu hiện tại không đúng.");
    }

    public static IdentityException khongTheAnDanhChinhMinh() {
        return new IdentityException(Kind.CONFLICT, "identity.tu_an_danh",
                "Không thể ẩn danh hoá tài khoản đang dùng để thực hiện thao tác này.",
                "ADMIN tự ẩn danh hoá mình sẽ tự khoá quyền quản trị và có thể để lại hệ "
                        + "thống không còn ADMIN nào");
    }

    public static IdentityException khongTuThaoTacVoiMinh(String thaoTac) {
        return new IdentityException(Kind.CONFLICT, "identity.tu_thao_tac",
                "Không thể tự " + thaoTac + " tài khoản đang dùng để thực hiện thao tác này.",
                "ADMIN tự " + thaoTac + " có thể để lại hệ thống không còn ADMIN nào");
    }
}
