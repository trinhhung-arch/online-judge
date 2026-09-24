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

    /** V15 · trần mã hai lớp theo tài khoản. Cùng hình dạng 429 + Retry-After với {@link #daKhoaTam}. */
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

    /**
     * FR-AUTH-01 · chống tạo tài khoản hàng loạt.
     *
     * <p>Câu chữ công khai KHÔNG nói ngưỡng là bao nhiêu và đã dùng hết mấy lượt. Nói ra là
     * đưa cho người viết bot đúng thông số họ cần để rải đều dưới ngưỡng.
     */
    public static IdentityException quaNhieuDangKy(Duration conLai) {
        return new IdentityException(Kind.RATE_LIMITED, "identity.dang_ky_qua_nhieu",
                "Đã tạo quá nhiều tài khoản từ địa chỉ này. Thử lại sau "
                        + Math.max(1, conLai.toMinutes()) + " phút.",
                "IP chạm giới hạn đăng ký", conLai);
    }

    /**
     * Quá nhiều lượt băm mật khẩu đang chạy cùng lúc — bỏ tải, không xếp hàng.
     *
     * <p>Đây là một câu nói về TẢI, không phải về người gọi: họ không làm gì sai. Nên câu
     * chữ mời thử lại thay vì trách móc, và {@code retryAfter} ngắn.
     */
    public static IdentityException heThongBan(Duration conLai) {
        return new IdentityException(Kind.RATE_LIMITED, "identity.he_thong_ban",
                "Hệ thống đang bận xử lý đăng nhập. Thử lại sau vài giây.",
                "Chạm trần oj.auth.bcrypt-concurrency", conLai);
    }

    /**
     * Captcha thiếu, sai, đã dùng, hoặc không hỏi lại được Cloudflare — cùng một câu cho cả bốn.
     *
     * <p>Phân biệt "thiếu" với "sai" là nói cho người viết bot biết trường nào server thật sự
     * đọc, và phân biệt "Cloudflare không trả lời" là chỉ cho họ một cửa để nhắm vào.
     */
    public static IdentityException captchaKhongHopLe() {
        return new IdentityException(Kind.INVALID, "identity.captcha_khong_hop_le",
                "Xác minh chống bot không thành công. Hãy thử lại.",
                "Turnstile từ chối hoặc không hỏi lại được");
    }

    /** Cùng một tài khoản đăng nhập lại quá nhanh — chỉ áp cho lượt THÀNH CÔNG. */
    public static IdentityException dangNhapQuaNhanh(Duration conLai) {
        return new IdentityException(Kind.RATE_LIMITED, "identity.dang_nhap_qua_nhanh",
                "Bạn vừa đăng nhập xong. Thử lại sau vài giây.",
                "Chạm oj.auth.login-min-interval", conLai);
    }

    // -------------------------------------------------------------------------
    // Xác thực hai lớp — V11
    // -------------------------------------------------------------------------

    /**
     * Mật khẩu ĐÚNG nhưng còn thiếu mã 2FA.
     *
     * <p>Đây là lần duy nhất hệ thống nói ra rằng một tài khoản có bật 2FA — và nó chỉ nói
     * sau khi mật khẩu đã đúng, nên không dùng để dò tài khoản nào có 2FA được.
     */
    public static IdentityException canTotp() {
        return new IdentityException(Kind.UNAUTHENTICATED, "identity.can_totp",
                "Tài khoản này bật xác thực hai lớp. Nhập mã 6 chữ số từ ứng dụng.",
                "Đăng nhập đúng mật khẩu, chờ mã TOTP");
    }

    /**
     * Mã sai, hết hạn, hoặc ĐÃ DÙNG RỒI — cùng một câu cho cả ba.
     *
     * <p>Phân biệt "sai" với "đã dùng" là nói cho kẻ phát lại biết rằng mã họ bắt được từng
     * đúng, tức là xác nhận họ bắt đúng chỗ.
     */
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

    /**
     * ADMIN chưa bật 2FA thì không dùng được quyền ADMIN.
     *
     * <p>KHÔNG chặn đăng nhập, và đó là điểm quan trọng: chặn đăng nhập thì họ không vào
     * được để mà bật, còn ở đây họ vẫn vào như một người dùng thường và bật được ngay.
     */
    public static IdentityException adminPhaiBatHaiLop() {
        return new IdentityException(Kind.FORBIDDEN, "identity.admin_can_2fa",
                "Tài khoản quản trị phải bật xác thực hai lớp mới dùng được quyền quản trị.",
                "ADMIN chưa bật 2FA — oj.auth.require-admin-two-factor đang bật");
    }

    // ------------------------- Xác minh email (V13, FR-AUTH-09) --------------

    /**
     * Mã sai, hết hạn, đã dùng, hoặc chưa từng có mã nào — cùng một câu cho cả bốn.
     *
     * <p>Cùng lập luận với {@link #totpSai()}: phân biệt "sai" với "hết hạn" là nói cho người
     * dò biết mã họ thử có từng đúng không. Với một mã chỉ có một triệu khả năng thì thông
     * tin ấy không cho không được. Câu công khai vẫn chỉ ra lối đi tiếp — xin một mã mới.
     */
    public static IdentityException maXacMinhSai() {
        return new IdentityException(Kind.INVALID, "identity.ma_xac_minh_sai",
                "Mã xác minh không đúng hoặc đã hết hạn. Hãy bấm gửi lại để nhận mã mới.",
                "Mã xác minh email không khớp (không ghi mã đã thử vào đây — bất biến #9)");
    }

    /**
     * Gõ sai quá {@code max-attempts} lần: mã đã bị khai tử.
     *
     * <p>Nói thẳng được vì người nhận câu này đã đăng nhập — họ là chủ tài khoản, và cần
     * biết vì sao mã trong hộp thư của mình đột nhiên không dùng được nữa.
     */
    public static IdentityException maXacMinhDaChet() {
        return new IdentityException(Kind.INVALID, "identity.ma_xac_minh_da_chet",
                "Đã nhập sai quá nhiều lần nên mã này không còn dùng được. "
                        + "Hãy bấm gửi lại để nhận mã mới.",
                "Mã xác minh email chạm trần max-attempts và đã bị khai tử");
    }

    /**
     * Bấm gửi lại quá nhanh — {@code resend-cooldown}.
     *
     * <p>Giới hạn này bảo vệ <b>hộp thư của người dùng</b>, không bảo vệ hệ thống: không có
     * khoảng chờ thì một vòng lặp là một trận dội thư vào đúng địa chỉ của chính chủ tài khoản.
     */
    public static IdentityException guiLaiQuaNhanh(Duration conLai) {
        return new IdentityException(Kind.RATE_LIMITED, "identity.gui_lai_qua_nhanh",
                "Vừa gửi một mã rồi. Kiểm tra hộp thư, hoặc thử lại sau "
                        + Math.max(1, conLai.toSeconds()) + " giây.",
                "Chạm oj.auth.email-verification.resend-cooldown", conLai);
    }

    /** Đã xác minh rồi — nói ra để giao diện khỏi mời bấm lần nữa. */
    public static IdentityException emailDaXacMinh() {
        return new IdentityException(Kind.CONFLICT, "identity.email_da_xac_minh",
                "Email của tài khoản này đã được xác minh.",
                "Gọi gửi/xác minh khi email_verified_at đã có giá trị");
    }

    /**
     * Máy chủ này chưa bật xác minh email.
     *
     * <p>Nói thẳng thay vì im lặng trả 204: bấm "gửi mã" rồi ngồi chờ một lá thư không bao
     * giờ tới là kiểu hỏng tệ nhất — không có gì trên màn hình sai, và không có gì nói vì sao.
     */
    public static IdentityException xacMinhEmailTat() {
        return new IdentityException(Kind.CONFLICT, "identity.xac_minh_email_tat",
                "Máy chủ này chưa bật xác minh email.",
                "oj.auth.email-verification.enabled = false");
    }

    /**
     * Không gửi được thư — SMTP không trả lời, từ chối, hoặc timeout.
     *
     * <p>{@code UNAVAILABLE} chứ không phải {@code INVALID}: người gọi không làm gì sai. Câu
     * chữ không nhắc tên máy chủ hay nội dung lỗi — đó là thông tin hạ tầng, thuộc về log.
     */
    public static IdentityException khongGuiDuocThu() {
        return new IdentityException(Kind.UNAVAILABLE, "identity.khong_gui_duoc_thu",
                "Không gửi được thư xác minh lúc này. Hãy thử lại sau ít phút.",
                "SMTP từ chối hoặc không kết nối được");
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

    /**
     * FR-ADM-03 — ADMIN không tự đổi vai trò và không tự vô hiệu hoá mình.
     *
     * <p>Cùng lý do với {@link #khongTheAnDanhChinhMinh()}: hệ thống có thể còn đúng một
     * ADMIN, và thao tác ấy khoá vĩnh viễn mọi đường quản trị. Không có "quên mật khẩu" nào
     * lấy lại được một vai trò.
     */
    public static IdentityException khongTuThaoTacVoiMinh(String thaoTac) {
        return new IdentityException(Kind.CONFLICT, "identity.tu_thao_tac",
                "Không thể tự " + thaoTac + " tài khoản đang dùng để thực hiện thao tác này.",
                "ADMIN tự " + thaoTac + " có thể để lại hệ thống không còn ADMIN nào");
    }
}
