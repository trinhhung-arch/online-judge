package dev.oj.identity.domain;

import dev.oj.platform.error.DomainException;

import java.time.Duration;

/**
 * Ngoại lệ của module {@code identity} — {@code CLAUDE.md} mục 7: mỗi module một exception riêng.
 *
 * <h2>★ Vì sao mọi lỗi đăng nhập đều là MỘT câu duy nhất</h2>
 * {@link #saiThongTinDangNhap()} trả cùng một {@code publicMessage} cho cả bốn trường hợp:
 * handle không tồn tại · email không tồn tại · mật khẩu sai · tài khoản đã bị vô hiệu hoá.
 *
 * <p>Phân biệt chúng là tặng không một <b>máy dò tài khoản</b>: gõ thử một nghìn email, cái
 * nào trả "mật khẩu sai" thay vì "không tìm thấy" thì cái đó có thật. Với một Online Judge,
 * danh sách email người dùng là thứ không có lý do gì phải lộ.
 *
 * <p>Cái giá là một người thật gõ nhầm email sẽ không được nói cho biết là họ gõ nhầm email.
 * Đó là cái giá đúng, và mọi hệ thống đăng nhập nghiêm túc đều trả nó.
 */
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

    // -------------------------------------------------------------------------
    // Đăng ký
    // -------------------------------------------------------------------------

    public static IdentityException khongHopLe(String code, String publicMessage) {
        return new IdentityException(Kind.INVALID, code, publicMessage);
    }

    /**
     * Handle hoặc email đã có người dùng.
     *
     * <p><b>Đây là chỗ DUY NHẤT hệ thống thừa nhận một danh tính có tồn tại</b>, và nó không
     * tránh được: không nói thì người đăng ký không hiểu vì sao form của họ bị từ chối. Bù lại
     * bằng chỗ khác — {@code LoginUseCase} không phân biệt, và rate limit theo IP (FR-AUTH-08)
     * làm việc dò hàng loạt qua đường này trở nên đắt.
     */
    public static IdentityException daTonTai(String truong) {
        return new IdentityException(Kind.CONFLICT, "identity.da_ton_tai",
                truong + " này đã có người dùng. Hãy chọn " + truong + " khác.",
                "Đăng ký trùng " + truong);
    }

    // -------------------------------------------------------------------------
    // Đăng nhập
    // -------------------------------------------------------------------------

    /** ★ Một câu cho bốn nguyên nhân — xem javadoc của class. */
    public static IdentityException saiThongTinDangNhap() {
        return new IdentityException(Kind.UNAUTHENTICATED, "identity.sai_thong_tin",
                "Tên đăng nhập hoặc mật khẩu không đúng.",
                "Đăng nhập thất bại (không ghi handle đã thử vào đây — bất biến #9)");
    }

    public static IdentityException daKhoaTam(Duration conLai) {
        return new IdentityException(Kind.RATE_LIMITED, "identity.khoa_tam",
                "Sai quá nhiều lần. Thử lại sau " + conLai.toMinutes() + " phút.",
                "IP bị khoá đăng nhập tạm thời", conLai);
    }

    // -------------------------------------------------------------------------
    // Phiên
    // -------------------------------------------------------------------------

    public static IdentityException phienKhongHopLe() {
        return new IdentityException(Kind.UNAUTHENTICATED, "identity.phien_khong_hop_le",
                "Phiên đăng nhập đã hết hạn. Hãy đăng nhập lại.");
    }

    /**
     * Một refresh token đã bị thu hồi lại được trình ra lần nữa.
     *
     * <p>Token đã xoay vòng thì bản cũ không còn ở đâu ngoài máy người dùng — nên bản cũ
     * quay lại nghĩa là <b>có hai bản đang tồn tại</b>, tức là một bản đã bị sao chép.
     * {@code RefreshSessionUseCase} thu hồi toàn bộ phiên của người đó khi thấy chuyện này.
     */
    public static IdentityException phienBiDungLai() {
        return new IdentityException(Kind.UNAUTHENTICATED, "identity.phien_bi_dung_lai",
                "Phiên đăng nhập không còn hiệu lực. Hãy đăng nhập lại.",
                "Refresh token đã thu hồi được trình lại — nghi ngờ token bị đánh cắp, "
                        + "đã thu hồi toàn bộ phiên của tài khoản");
    }

    // -------------------------------------------------------------------------
    // Tài khoản
    // -------------------------------------------------------------------------

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
}
