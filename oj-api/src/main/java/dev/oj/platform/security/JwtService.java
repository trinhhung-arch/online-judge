package dev.oj.platform.security;

import dev.oj.platform.config.AppProperties;
import dev.oj.platform.security.CurrentUserProvider.CurrentUser;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;

/**
 * Phát và kiểm access token — Bước 4.5, FR-AUTH-02.
 *
 * <h2>Access token mang vai trò, và không tra database</h2>
 * Đó là lý do nó rẻ: mỗi request được phân quyền mà không tốn một lượt đi Postgres. Cái giá
 * là token <b>cũ</b> — hạ vai trò một người từ ADMIN xuống USER thì token đã phát vẫn còn
 * ADMIN cho tới khi hết hạn. {@code oj.auth.access-ttl} là trần của khoảng cũ đó, và
 * {@link AuthProperties} crash lúc boot nếu ai đó kéo nó quá 15 phút.
 *
 * <p>Muốn hạ quyền có hiệu lực <i>ngay</i> thì phải thu hồi refresh token (một dòng UPDATE)
 * và chờ tối đa 15 phút. Không có đường tắt nào rẻ hơn mà không phải tra database mỗi request.
 *
 * <h2>{@code ObjectMapper} riêng, không dùng bean của ứng dụng</h2>
 * Bean chung được cấu hình cho <i>dữ liệu API</i>: Spring Boot tắt
 * {@code FAIL_ON_UNKNOWN_PROPERTIES} để client cũ gửi thừa trường vẫn chạy. Đó là mặc định
 * đúng cho một API công khai và là mặc định <b>sai</b> cho thứ quyết định bạn là ai. Mapper ở
 * đây bật lại nó: một token mang trường lạ là một token ta không phát ra, và câu trả lời đúng
 * là từ chối chứ không phải bỏ qua phần không hiểu.
 *
 * <p>Hệ quả phải biết: <b>thêm một claim mới là làm mọi token đang lưu hành hỏng</b> trong tối
 * đa 15 phút, vì phiên bản cũ của service sẽ không đọc được token do phiên bản mới phát ra.
 * Với TTL 15 phút thì đó là một cửa sổ tự đóng, không phải một sự cố.
 */
@Component
public class JwtService {

    /**
     * Claim của một access token. Cố ý chỉ có bốn trường — đúng thứ
     * {@link CurrentUser} cần, không hơn.
     *
     * <p>Đừng thêm {@code email} vào đây. Token đi qua log của proxy, qua thanh địa chỉ khi ai
     * đó copy nhầm, qua ảnh chụp màn hình lúc báo lỗi — và phần payload của JWT
     * <b>không mã hoá</b>, chỉ mã hoá base64. Bất cứ ai cầm token đều đọc được mọi thứ trong
     * này bằng một dòng lệnh (bất biến #9).
     *
     * @param sub    {@code users.id}
     * @param handle tên đăng nhập, để hiển thị và ghi {@code audit_log}
     * @param role   vai trò <b>tại thời điểm phát token</b>
     * @param exp    hạn dùng, giây Unix — cùng đơn vị với chuẩn JWT
     */
    record Claims(long sub, String handle, String role, long exp) {
    }

    private final Jwt jwt;
    private final ObjectMapper json;
    private final java.time.Duration ttl;
    private final Clock clock;

    public JwtService(AppProperties properties, Clock clock) {
        this.jwt = new Jwt(properties.auth().jwtSecret());
        this.ttl = properties.auth().accessTtl();
        this.clock = clock;
        this.json = JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    /** @return token compact {@code header.payload.signature} */
    public String phat(CurrentUser user) {
        Claims claims = new Claims(user.id(), user.handle(), user.role().name(),
                clock.instant().plus(ttl).getEpochSecond());
        return jwt.ky(json.writeValueAsBytes(claims));
    }

    /**
     * @throws AuthorizationException {@code auth.token_khong_hop_le} nếu chữ ký sai, định dạng
     *         hỏng, hoặc claim không đúng hình dạng ta phát ra;
     *         {@code auth.token_het_han} nếu quá {@code exp}
     */
    public CurrentUser doc(String token) {
        byte[] payload = jwt.moKhoa(token);   // chữ ký đã đúng khi hàm này trả về

        Claims claims;
        try {
            claims = json.readValue(new String(payload, StandardCharsets.UTF_8), Claims.class);
        } catch (JacksonException e) {
            // Chữ ký đúng nhưng hình dạng sai: hoặc khoá ký bị dùng chung với một hệ thống
            // khác, hoặc ta vừa deploy một phiên bản đổi claim. Cả hai đều là "đăng nhập lại".
            throw AuthorizationException.tokenKhongHopLe();
        }

        if (Instant.ofEpochSecond(claims.exp()).isBefore(clock.instant())) {
            throw AuthorizationException.tokenHetHan();
        }

        try {
            return new CurrentUser(claims.sub(), claims.handle(), Role.fromCode(claims.role()));
        } catch (IllegalArgumentException | NullPointerException e) {
            // Vai trò đã bị xoá khỏi enum giữa hai lần deploy, hoặc id/handle rỗng.
            throw AuthorizationException.tokenKhongHopLe();
        }
    }

    /** Giây còn lại của một access token vừa phát — để client biết khi nào nên làm mới. */
    public long hanDungGiay() {
        return ttl.toSeconds();
    }
}
