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

    /**
     * Thu hồi một token, ghi luôn token nào thay thế nó ({@code null} khi chưa biết).
     *
     * <p>★ Đây là một phép <b>so-rồi-đổi nguyên tử</b>: chỉ đổi dòng còn {@code revoked_at IS
     * NULL}, trong đúng một câu lệnh. Hai lời gọi song song trên cùng một token thì Postgres
     * xếp hàng chúng bằng khoá dòng, và lời gọi thứ hai thấy dòng đã bị thu hồi.
     *
     * @return {@code true} nếu CHÍNH lời gọi này thu hồi token; {@code false} nếu token đã bị
     *         thu hồi trước đó — kể cả bởi một request khác chỉ vừa kịp trước vài micro giây.
     *         {@code RefreshSessionUseCase} dựa vào giá trị này để phát hiện token bị sao chép
     */
    boolean thuHoi(long tokenId, String lyDo, Long thayTheBoiId);

    /**
     * Nối mắt xích {@code replaced_by_id} cho một token đã bị thu hồi khi xoay vòng.
     *
     * <p>Tách khỏi {@link #thuHoi} vì thứ tự: token cũ phải bị thu hồi TRƯỚC khi token mới được
     * sinh ra (đó là chốt chống đua), nên lúc thu hồi thì id của token mới chưa tồn tại.
     */
    void ganThayThe(long tokenId, long thayTheBoiId);

    /**
     * Thu hồi mọi phiên còn sống của một người. Dùng cho FR-AUTH-04 (đổi mật khẩu),
     * FR-AUTH-07 (ẩn danh hoá), và khi phát hiện token bị dùng lại.
     *
     * @return số phiên đã thu hồi — con số này đi vào {@code audit_log}
     */
    int thuHoiTatCa(long userId, String lyDo);
}
