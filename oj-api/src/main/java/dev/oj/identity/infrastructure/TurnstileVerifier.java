package dev.oj.identity.infrastructure;

import dev.oj.identity.application.port.CaptchaVerifier;
import dev.oj.identity.domain.IdentityException;
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.config.TurnstileProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Hỏi lại Cloudflare xem token captcha có thật không — FR-AUTH-01.
 *
 * <h2>Không thêm dependency nào</h2>
 * {@link RestClient} đi cùng {@code spring-web}, thứ {@code starter-webmvc} đã kéo vào. Toàn
 * bộ giao thức siteverify là một {@code POST} form với hai trường.
 *
 * <h2>★ Hỏng thì TỪ CHỐI, cùng lập trường với rate limit đăng ký</h2>
 * Cloudflare không trả lời (mạng chập, timeout) thì lượt đăng ký ấy bị từ chối. Cho qua nghĩa
 * là mở đúng cái cửa này vào lúc không kiểm được gì — và một bot chỉ cần làm nghẽn đường ra
 * internet là vô hiệu hoá cả hàng rào.
 *
 * <p>Cái giá nhỏ và đối xứng với {@code RedisRegistrationRateLimiter}: hoãn một lượt đăng ký
 * thì người ta bấm lại; người đã có tài khoản vẫn đăng nhập, vẫn nộp bài, vì cả hai đường đó
 * không đi qua class này.
 *
 * <h2>★ Vì sao vẫn là bean khi tính năng TẮT</h2>
 * Để {@code RegisterUserUseCase} có đúng một đường mã, không có nhánh {@code if (enabled)}
 * rải ở tầng use-case. Tắt thì {@link #kiem} trả về ngay — quyết định nằm ở một chỗ.
 */
@Component
public class TurnstileVerifier implements CaptchaVerifier {

    private static final Logger log = LoggerFactory.getLogger(TurnstileVerifier.class);

    private final TurnstileProperties properties;
    private final RestClient http;

    public TurnstileVerifier(AppProperties app, RestClient.Builder builder) {
        this.properties = app.auth().turnstile();
        this.http = builder.build();
        if (!properties.enabled()) {
            // WARN chứ không INFO: một máy công khai quên bật thì dòng này là thứ duy nhất
            // nói ra điều đó, và nó phải nổi lên giữa nhật ký khởi động.
            log.warn("Turnstile TẮT — cửa đăng ký chỉ còn giới hạn 10 tài khoản/giờ/IP. "
                    + "Trên máy công khai hãy đặt OJ_TURNSTILE_ENABLED=true.");
        }
    }

    @Override
    public void kiem(String token, String clientIp) {
        if (!properties.enabled()) {
            return;
        }
        if (token == null || token.isBlank()) {
            throw IdentityException.captchaKhongHopLe();
        }

        var than = new LinkedMultiValueMap<String, String>();
        than.add("secret", properties.secret());
        than.add("response", token);
        if (clientIp != null) {
            than.add("remoteip", clientIp);
        }

        Map<?, ?> traLoi;
        try {
            traLoi = http.post()
                    .uri(properties.verifyUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(than)
                    .retrieve()
                    .body(Map.class);
        } catch (RuntimeException e) {
            // KHÔNG ghi token vào log (bất biến #9): nó là thông tin xác thực dùng một lần.
            log.warn("Không hỏi được Turnstile ({}). Từ chối lượt đăng ký này — xem javadoc "
                    + "để biết vì sao không cho qua.", e.toString());
            throw IdentityException.captchaKhongHopLe();
        }

        if (traLoi == null || !Boolean.TRUE.equals(traLoi.get("success"))) {
            // `error-codes` của Cloudflare nói vì sao hỏng — hữu ích khi gỡ lỗi cấu hình,
            // và nó không chứa gì bí mật.
            log.debug("Turnstile từ chối: {}", traLoi == null ? "không có thân" : traLoi.get("error-codes"));
            throw IdentityException.captchaKhongHopLe();
        }
    }

    /**
     * {@code RestClient.Builder} KHÔNG được auto-config trong ngữ cảnh này — đây là ứng dụng
     * webmvc, không phải webclient, nên không bean nào tồn tại sẵn.
     *
     * <p>Đo thật ngày 2026-09-05: thiếu bean này làm cả context không dựng được, và triệu
     * chứng là {@code NoSuchBeanDefinitionException} ở một lớp IT ngẫu nhiên chứ không phải
     * ở chỗ gây ra.
     *
     * <p>{@code @ConditionalOnMissingBean} để ngày nào đó Spring Boot tự cấp thì bean ở đây
     * lặng lẽ nhường chỗ, thay vì đâm nhau.
     */
    @org.springframework.context.annotation.Configuration
    public static class HttpConfig {

        @org.springframework.context.annotation.Bean
        @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
        public RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }
    }
}
