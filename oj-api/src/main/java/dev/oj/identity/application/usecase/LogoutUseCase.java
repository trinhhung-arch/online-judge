package dev.oj.identity.application.usecase;

import dev.oj.identity.application.port.RefreshTokenRepository;
import dev.oj.identity.domain.RefreshTokenSecret;
import dev.oj.platform.security.PublicAccess;
import org.springframework.stereotype.Service;

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
