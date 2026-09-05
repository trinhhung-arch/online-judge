package dev.oj.identity.infrastructure;

import dev.oj.identity.application.port.PasswordHasher;
import dev.oj.identity.domain.IdentityException;
import dev.oj.platform.config.AppProperties;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * BCrypt cost 12 — FR-AUTH-01, Bước 4.4.
 *
 * <h2>★ Vì sao {@link #khop} vẫn tốn 250ms khi băm là {@code null}</h2>
 * {@code null} nghĩa là <i>tài khoản không tồn tại</i>, hoặc đã ẩn danh hoá. Trả {@code false}
 * ngay lập tức là để thời gian phản hồi tự khai điều đó: nhánh "không có tài khoản" mất 2ms,
 * nhánh "sai mật khẩu" mất 250ms, và người dò chỉ cần bấm giờ để lọc ra danh sách email có
 * thật. Không cần đọc nội dung response, không bị rate limit chặn vì mỗi email chỉ thử một lần.
 *
 * <h2>★ TRẦN CỨNG cho số lần băm chạy song song — và vì sao nó phải TỪ CHỐI, không xếp hàng</h2>
 * 250ms CPU mỗi lần băm là tính năng cho tới lúc có người gọi nó 100 lần một giây. FR-AUTH-08
 * chỉ khoá các lượt <b>SAI</b>, nên một bot có 1 000 tài khoản hợp lệ chỉ cần đăng nhập ĐÚNG
 * liên tục là ăn hết CPU — không phải đoán gì, vì chính nó đặt mật khẩu lúc đăng ký. Đo ngày
 * 2026-09-05: <b>17,5 lượt/giây làm nghẽn cả máy</b>, và lúc ấy chấm bài đứng.
 *
 * <p>{@link Semaphore#tryAcquire} có hạn chờ NGẮN rồi ném, chứ không {@code acquire()} chờ mãi.
 * Chờ nghĩa là giữ luồng Tomcat; 200 luồng bị giữ thì đọc đề, nộp bài, ghi verdict đều chết
 * theo — đúng thứ hàng rào này sinh ra để ngăn. Bỏ tải là cách duy nhất giữ phần còn lại thở.
 *
 * <p>Hàng rào đặt ở ĐÂY chứ không ở {@code LoginUseCase} vì mọi đường tốn CPU đều đi qua đây:
 * đăng nhập, đăng ký, đổi mật khẩu. Một chốt, không có đường vòng.
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
    private final Semaphore suat;
    private final Duration cho;

    public BCryptPasswordHasher(AppProperties properties) {
        this.encoder = new BCryptPasswordEncoder(properties.auth().bcryptCost());
        // fair = true: request đến trước được phục vụ trước. Không có nó, dưới tải nặng một
        // số request có thể chờ vô hạn định trong khi request mới liên tục chen lên.
        this.suat = new Semaphore(properties.auth().bcryptConcurrency(), true);
        this.cho = properties.auth().bcryptWait();
    }

    @Override
    public String bam(String matKhauTho) {
        return trongTran(() -> encoder.encode(matKhauTho));
    }

    @Override
    public boolean khop(String matKhauTho, String bamDaLuu) {
        return trongTran(() -> {
            if (bamDaLuu == null) {
                encoder.matches(matKhauTho, BAM_GIA);
                return false;
            }
            return encoder.matches(matKhauTho, bamDaLuu);
        });
    }

    /**
     * Xin một suất, làm việc, trả suất. {@code finally} là bắt buộc: một lần quên trả suất
     * là một suất mất vĩnh viễn, và sau vài lần thì không ai đăng nhập được nữa.
     */
    private <T> T trongTran(java.util.function.Supplier<T> viec) {
        boolean coSuat;
        try {
            coSuat = suat.tryAcquire(cho.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw IdentityException.heThongBan(cho);
        }
        if (!coSuat) {
            throw IdentityException.heThongBan(Duration.ofSeconds(5));
        }
        try {
            return viec.get();
        } finally {
            suat.release();
        }
    }
}
