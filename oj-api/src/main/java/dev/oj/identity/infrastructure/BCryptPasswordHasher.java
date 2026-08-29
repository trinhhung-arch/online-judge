package dev.oj.identity.infrastructure;

import dev.oj.identity.application.port.PasswordHasher;
import dev.oj.platform.config.AppProperties;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * BCrypt cost 12 — FR-AUTH-01, Bước 4.4.
 *
 * <h2>★ Vì sao {@link #khop} vẫn tốn 250ms khi băm là {@code null}</h2>
 * {@code null} nghĩa là <i>tài khoản không tồn tại</i>, hoặc đã ẩn danh hoá. Trả {@code false}
 * ngay lập tức là để thời gian phản hồi tự khai điều đó: nhánh "không có tài khoản" mất 2ms,
 * nhánh "sai mật khẩu" mất 250ms, và người dò chỉ cần bấm giờ để lọc ra danh sách email có
 * thật. Không cần đọc nội dung response, không bị rate limit chặn vì mỗi email chỉ thử một lần.
 *
 * <p>Nên nhánh {@code null} băm với {@link #BAM_GIA} — một băm BCrypt hợp lệ của một chuỗi
 * không ai biết — rồi vứt kết quả đi. Chi phí bằng nhau, thời gian bằng nhau, không rò rỉ gì.
 *
 * <p>Đây là lý do {@link PasswordHasher#khop} nhận {@code null} thay vì để người gọi kiểm
 * trước: một hợp đồng đúng ở đúng một chỗ, thay vì một câu {@code if} mà mọi chỗ gọi phải nhớ.
 *
 * <h2>Cost 12 tốn khoảng 250ms, và đó là tính năng</h2>
 * Nó nằm ngoài đường nóng ({@code POST /submissions} không băm gì cả), nên 250ms ở
 * {@code /auth/login} không tiêu ngân sách nào của {@code nfrplan.md} 2.1. Đổi lại, một GPU
 * dò offline từ một bản database bị lộ chạy chậm hơn khoảng bốn nghìn lần so với cost thấp.
 */
@Component
public class BCryptPasswordHasher implements PasswordHasher {

    /**
     * Băm BCrypt cost 12 của một chuỗi ngẫu nhiên đã bị quên. Không có mật khẩu nào khớp nó,
     * và đó là toàn bộ mục đích: nó chỉ để <b>tốn thời gian</b>.
     */
    private static final String BAM_GIA =
            "$2a$12$C6UzMDM.H6dfI/f/IKcEe.7lNlS3ITwYqLzKz9M4pOSf5lXO4/Bxu";

    private final BCryptPasswordEncoder encoder;

    public BCryptPasswordHasher(AppProperties properties) {
        this.encoder = new BCryptPasswordEncoder(properties.auth().bcryptCost());
    }

    @Override
    public String bam(String matKhauTho) {
        return encoder.encode(matKhauTho);
    }

    @Override
    public boolean khop(String matKhauTho, String bamDaLuu) {
        if (bamDaLuu == null) {
            encoder.matches(matKhauTho, BAM_GIA);
            return false;
        }
        return encoder.matches(matKhauTho, bamDaLuu);
    }
}
