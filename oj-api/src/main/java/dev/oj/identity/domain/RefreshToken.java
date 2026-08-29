package dev.oj.identity.domain;

import java.time.Instant;

/**
 * Một phiên đăng nhập dài hạn — 7 ngày (FR-AUTH-02). Đây là bản ghi trong database; giá trị
 * thật mà người dùng cầm nằm ở {@link RefreshTokenSecret} và <b>không</b> có mặt ở đây.
 *
 * <h2>Vì sao phiên là một dòng trong database, còn access token thì không</h2>
 * Hai thứ này đánh đổi ngược nhau, và đó là ý đồ:
 *
 * <ul>
 *   <li><b>Access token</b> (15 phút) không tra database — vì thế nó rẻ, và vì thế nó không
 *       thu hồi được. Chấp nhận được vì nó chết sau 15 phút.</li>
 *   <li><b>Refresh token</b> (7 ngày) tra database mỗi lần dùng — vì thế nó chậm hơn, và vì
 *       thế nó <i>thu hồi được ngay lập tức</i>. Đó là điều kiện để FR-AUTH-03 (đăng xuất) và
 *       FR-AUTH-04 (đổi mật khẩu thu hồi mọi phiên) có nghĩa gì đó thay vì chỉ xoá một cookie
 *       trên máy người dùng.</li>
 * </ul>
 *
 * <p>Nếu cả hai đều không tra database thì "đăng xuất" là một lời nói dối: token bị đánh cắp
 * vẫn dùng được cho tới ngày hết hạn.
 *
 * @param revokedAt {@code null} nghĩa là còn hiệu lực. Không xoá dòng khi thu hồi —
 *                  {@code replacedById} trong schema cần dòng cũ còn đó để lần được chuỗi xoay
 *                  vòng, và đó là cách phát hiện token bị đánh cắp
 */
public record RefreshToken(
        long id,
        long userId,
        Instant issuedAt,
        Instant expiresAt,
        Instant revokedAt) {

    public boolean daThuHoi() {
        return revokedAt != null;
    }

    public boolean daHetHan(Instant bayGio) {
        return !bayGio.isBefore(expiresAt);
    }

    public boolean conHieuLuc(Instant bayGio) {
        return !daThuHoi() && !daHetHan(bayGio);
    }
}
