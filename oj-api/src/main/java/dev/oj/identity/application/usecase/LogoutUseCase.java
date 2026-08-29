package dev.oj.identity.application.usecase;

import dev.oj.identity.application.port.RefreshTokenRepository;
import dev.oj.identity.domain.RefreshTokenSecret;
import dev.oj.platform.security.PublicAccess;
import org.springframework.stereotype.Service;

/**
 * FR-AUTH-03 — đăng xuất: thu hồi refresh token đang dùng.
 *
 * <h2>Im lặng khi token không tồn tại — cố ý</h2>
 * Đăng xuất là <b>idempotent</b>. Bấm hai lần, bấm sau khi phiên đã hết hạn, bấm bằng một
 * token của người khác — tất cả đều trả 204. Hai lý do:
 *
 * <ul>
 *   <li>Trả lỗi khi đăng xuất là buộc người dùng ở lại trong một phiên họ vừa nói là muốn
 *       rời khỏi. Frontend sẽ xoá token nội bộ dù server trả gì, nên lỗi ở đây chỉ tạo ra
 *       một thông báo vô nghĩa.</li>
 *   <li>Phân biệt "token này có tồn tại" với "không" là một máy dò token, và nó rẻ hơn hẳn
 *       máy dò qua đường đăng nhập vì không có mật khẩu nào phải đoán.</li>
 * </ul>
 *
 * <p><b>Access token vẫn sống thêm tối đa 15 phút</b> sau khi đăng xuất — nó không tra
 * database nên không thu hồi được. Đó là đánh đổi đã chọn ở {@code AppProperties.Auth}, và
 * frontend phải xoá nó khỏi bộ nhớ. Muốn cắt ngay lập tức thì phải tra database mỗi request,
 * tức là bỏ toàn bộ lợi ích của thiết kế này.
 */
@PublicAccess("Refresh token là thứ xác thực. Đòi access token còn hiệu lực nghĩa là không "
        + "đăng xuất được sau khi nó hết hạn — đúng lúc người dùng rời máy.")
@Service
public class LogoutUseCase {

    private final RefreshTokenRepository refreshTokens;

    public LogoutUseCase(RefreshTokenRepository refreshTokens) {
        this.refreshTokens = refreshTokens;
    }

    public void thucHien(String tokenTho) {
        if (tokenTho == null || tokenTho.isBlank()) {
            return;
        }
        refreshTokens.timTheoBam(RefreshTokenSecret.bam(tokenTho))
                .filter(token -> !token.daThuHoi())
                .ifPresent(token -> refreshTokens.thuHoi(token.id(), "người dùng đăng xuất", null));
    }
}
