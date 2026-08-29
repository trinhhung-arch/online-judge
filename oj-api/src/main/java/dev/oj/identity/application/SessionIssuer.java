package dev.oj.identity.application;

import dev.oj.identity.application.port.RefreshTokenRepository;
import dev.oj.identity.domain.RefreshTokenSecret;
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.security.CurrentUserProvider.CurrentUser;
import dev.oj.platform.security.JwtService;
import dev.oj.platform.security.Role;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

/**
 * Phát một cặp <b>access token + refresh token</b> — dùng chung bởi {@code LoginUseCase} và
 * {@code RefreshSessionUseCase}. FR-AUTH-02.
 *
 * <p>Cố ý <b>không</b> là một {@code *UseCase}: nó không phải một hành động người dùng yêu cầu
 * mà là một bước chung của hai hành động khác. Nếu nó mang tên {@code UseCase} thì LUẬT 8 sẽ
 * bắt nó tuyên bố lập trường phân quyền, và câu trả lời đúng lại là <i>"tuỳ người gọi"</i> —
 * một lập trường mà annotation không diễn đạt được, nên tốt nhất là đừng đặt câu hỏi.
 *
 * <p>Hai use-case gọi nó đều đã tự kiểm phần của mình: {@code Login} kiểm mật khẩu,
 * {@code Refresh} kiểm token cũ.
 */
@Service
public class SessionIssuer {

    /**
     * @param refreshToken giá trị <b>thô</b>. Nó chỉ tồn tại trong đúng một response HTTP;
     *                     database chỉ có băm của nó
     */
    public record Session(
            String accessToken,
            String refreshToken,
            long accessTtlSeconds,
            long userId,
            String handle,
            Role role) {
    }

    private final JwtService jwt;
    private final RefreshTokenRepository refreshTokens;
    private final AppProperties properties;
    private final Clock clock;

    public SessionIssuer(JwtService jwt, RefreshTokenRepository refreshTokens,
                         AppProperties properties, Clock clock) {
        this.jwt = jwt;
        this.refreshTokens = refreshTokens;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @param thayThe id của refresh token bị cặp này thay thế, hoặc {@code null} khi đăng nhập mới
     * @return phiên mới; token cũ (nếu có) đã được đánh dấu thu hồi và trỏ tới token mới
     */
    public Session phat(long userId, String handle, Role role,
                        String userAgent, String clientIp, Long thayThe) {
        Instant bayGio = clock.instant();
        RefreshTokenSecret secret = RefreshTokenSecret.sinh();
        long tokenId = refreshTokens.luu(userId, secret.sha256Hex(), bayGio,
                bayGio.plus(properties.auth().refreshTtl()), userAgent, clientIp);

        if (thayThe != null) {
            // Thu hồi SAU khi đã lưu token mới, để replaced_by_id trỏ được tới nó. Chuỗi này
            // là thứ duy nhất phát hiện được token bị sao chép — xem RefreshSessionUseCase.
            refreshTokens.thuHoi(thayThe, "xoay vòng", tokenId);
        }

        String accessToken = jwt.phat(new CurrentUser(userId, handle, role));
        return new Session(accessToken, secret.giaTriTho(), jwt.hanDungGiay(),
                userId, handle, role);
    }
}
