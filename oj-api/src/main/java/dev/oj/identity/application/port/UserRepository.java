package dev.oj.identity.application.port;

import dev.oj.identity.domain.Credentials;
import dev.oj.identity.domain.User;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Cổng ra database cho bảng {@code users}. Hiện thực ở {@code identity.infrastructure}.
 *
 * <h2>Vì sao {@link #timCredentials} tách riêng khỏi {@link #timTheoId}</h2>
 * Vì băm mật khẩu chỉ được đọc lên khi thật sự cần so sánh. Nếu {@link User} mang sẵn nó thì
 * mọi lần đọc hồ sơ, mọi lần hiển thị tên tác giả đề, mọi lần dựng bảng xếp hạng đều kéo băm
 * mật khẩu vào bộ nhớ — và từ đó vào một dòng log nào đó (bất biến #9). Xem javadoc của
 * {@link Credentials}.
 */
public interface UserRepository {

    /**
     * Tra theo handle <b>hoặc</b> email, không phân biệt hoa thường.
     *
     * <p>Một câu query cho cả hai: người dùng gõ vào một ô duy nhất và không nên phải nói cho
     * hệ thống biết họ vừa gõ loại nào.
     *
     * @return rỗng nếu không có ai — {@code LoginUseCase} vẫn phải chạy hết công đoạn so mật
     *         khẩu giả để thời gian phản hồi không tố cáo rằng tài khoản không tồn tại
     */
    Optional<Credentials> timCredentials(String handleHoacEmail);

    Optional<User> timTheoId(long id);

    /**
     * Cùng lý do với {@link #timCredentials}, nhưng cho một người đã đăng nhập:
     * {@code ChangePasswordUseCase} cần so mật khẩu cũ, và đó là chỗ duy nhất trong hệ thống
     * đọc băm mật khẩu của chính người đang gọi.
     */
    Optional<Credentials> timCredentialsTheoId(long userId);

    boolean daCoHandle(String handle);

    boolean daCoEmail(String email);

    /**
     * @return {@code users.id} vừa sinh
     * @throws dev.oj.identity.domain.IdentityException {@code CONFLICT} nếu chạm unique index —
     *         đây là chốt thật, phần kiểm trước đó chỉ để cho ra thông báo cụ thể hơn
     */
    long taoMoi(String handle, String email, String displayName, String passwordHash);

    /** FR-AUTH-05. {@code preferredLanguageId} có thể {@code null} để xoá lựa chọn. */
    void capNhatHoSo(long userId, String displayName, Short preferredLanguageId);

    /** FR-AUTH-04. Chỉ đổi cột băm; thu hồi phiên là việc của {@code RefreshTokenRepository}. */
    void doiMatKhau(long userId, String passwordHash);

    /**
     * FR-AUTH-07 — xoá email, xoá băm mật khẩu, đổi tên hiển thị, đặt trạng thái
     * {@code ANONYMIZED}. <b>Không xoá dòng</b>: {@code submissions.user_id} có khoá ngoại, và
     * thứ hạng của mọi kỳ thi người đó từng dự phụ thuộc vào dòng này còn tồn tại.
     */
    void anDanhHoa(long userId, String tenHienThiMoi);

    /**
     * FR-ADM-03 — ADMIN đổi vai trò. Bước 6.6.
     *
     * <p>Câu {@code UPDATE} mang {@code AND status <> 'ANONYMIZED'}: một tài khoản đã ẩn danh
     * hoá không được nhận lại vai trò nào. Điều kiện nằm <b>trong SQL</b> chứ không phải một
     * câu {@code if} ở use-case, cùng lý do với chống IDOR ({@code oj-api/CLAUDE.md} mục 2) —
     * một đường ghi thứ hai sau này sẽ quên câu {@code if}, nhưng không quên được câu query.
     *
     * @return {@code false} nếu không dòng nào khớp: người dùng không tồn tại, hoặc đã ẩn danh hoá
     */
    boolean doiVaiTro(long userId, String vaiTroMoi);

    /**
     * FR-ADM-03 — vô hiệu hoá / mở lại. <b>Không xoá cứng</b>, ở đây cũng như mọi nơi khác.
     *
     * <p>{@code DISABLED} khác {@code ANONYMIZED}: dữ liệu định danh còn nguyên và thao tác
     * đảo ngược được. Đó là công cụ cho tình huống thật — một tài khoản đang gian lận giữa kỳ
     * thi cần bị chặn <i>ngay</i>, và cần được mở lại sau khi làm rõ.
     *
     * @return {@code false} nếu không dòng nào khớp
     */
    boolean doiTrangThai(long userId, String trangThaiMoi);

    /**
     * ★ Danh sách người dùng cho ADMIN — FR-ADM-03.
     *
     * <h2>KHÔNG có {@code email}, và đó là một quyết định đã được ghi từ trước</h2>
     * Javadoc của {@code ProfileResponse} nói thẳng: <i>"nếu sau này cần hồ sơ công khai của
     * người khác thì đó là một record RIÊNG không có trường này — đừng thêm một cờ
     * {@code boolean anGiau}, vì một cờ đặt sai là một lần rò rỉ toàn bộ danh sách email
     * người dùng."</i>
     *
     * <p>Đây <b>chính là</b> cái endpoint mà câu ấy cảnh báo: nó trả về mọi người dùng, một
     * trang một lần. Quản lý vai trò cần {@code handle} để nhận ra người, không cần email —
     * nên email không có mặt, và không có cờ nào bật nó lên được.
     *
     * @param tim   chuỗi con của {@code handle}, không phân biệt hoa thường; {@code null} =
     *              lấy tất cả. Ký tự {@code %} và {@code _} của người dùng đã được thoát —
     *              {@code handle} hợp lệ chứa được {@code _}, nên không thoát là "a_b" khớp
     *              cả "axb"
     * @param sauId con trỏ trang: chỉ lấy id NHỎ HƠN (thứ tự giảm dần); {@code null} = trang đầu
     */
    List<TomTatNguoiDung> danhSach(String tim, Long sauId, int gioiHan);

    /** Không có email — xem javadoc của {@link #danhSach}. */
    record TomTatNguoiDung(long id, String handle, String displayName,
                           String vaiTro, String trangThai, Instant taoLuc) {
    }
}
