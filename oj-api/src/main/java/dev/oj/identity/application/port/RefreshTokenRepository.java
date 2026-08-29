package dev.oj.identity.application.port;

import dev.oj.identity.domain.RefreshToken;

import java.time.Instant;
import java.util.Optional;

/**
 * Cổng ra bảng {@code refresh_tokens}. Lưu <b>băm SHA-256</b>, không lưu token thô — Bước 4.4.
 *
 * <h2>Không có hàm xoá, và đó là chủ ý</h2>
 * Thu hồi là {@code UPDATE ... SET revoked_at = now()}, không phải {@code DELETE}. Dòng cũ
 * phải còn lại để {@code replaced_by_id} lần được chuỗi xoay vòng, và chuỗi đó là <b>cách
 * duy nhất</b> phát hiện một token đã bị sao chép: nếu một mắt xích cũ quay lại, nghĩa là có
 * hai bản đang tồn tại.
 */
public interface RefreshTokenRepository {

    /** @return {@code refresh_tokens.id} vừa sinh — cần cho {@code replaced_by_id} khi xoay vòng */
    long luu(long userId, String tokenSha256, Instant phatLuc, Instant hetHan,
             String userAgent, String clientIp);

    Optional<RefreshToken> timTheoBam(String tokenSha256);

    /** Thu hồi một token, ghi luôn token nào thay thế nó ({@code null} khi đăng xuất). */
    void thuHoi(long tokenId, String lyDo, Long thayTheBoiId);

    /**
     * Thu hồi mọi phiên còn sống của một người. Dùng cho FR-AUTH-04 (đổi mật khẩu),
     * FR-AUTH-07 (ẩn danh hoá), và khi phát hiện token bị dùng lại.
     *
     * @return số phiên đã thu hồi — con số này đi vào {@code audit_log}
     */
    int thuHoiTatCa(long userId, String lyDo);
}
