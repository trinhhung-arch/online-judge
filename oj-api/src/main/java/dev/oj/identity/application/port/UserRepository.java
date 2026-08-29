package dev.oj.identity.application.port;

import dev.oj.identity.domain.Credentials;
import dev.oj.identity.domain.User;

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
}
