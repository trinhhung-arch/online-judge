package dev.oj.identity.infrastructure;

import dev.oj.identity.application.port.CaptchaVerifier;
import dev.oj.identity.domain.IdentityException;
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.config.TurnstileProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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

    /** Chữ thường, dựng một lần: tên miền không phân biệt hoa thường. */
    private final Set<String> hostnames;

    /**
     * @param builder builder ĐÃ mang timeout — xem {@link HttpConfig}. Test truyền một builder
     *                gắn {@code MockRestServiceServer}, nên class này không được tự đặt lại
     *                request factory: làm thế là ghi đè máy chủ giả và test gọi ra internet
     */
    public TurnstileVerifier(AppProperties app, @Qualifier(HttpConfig.BEAN) RestClient.Builder builder) {
        this.properties = app.auth().turnstile();
        this.http = builder.build();
        this.hostnames = properties.hostnames().stream()
                .map(h -> h.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
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
        kiemNoiGiai(traLoi.get("hostname"));
    }

    /**
     * {@code success=true} chỉ nói token có thật — không nói nó được giải ở đâu. Xem mục
     * {@code hostnames} ở {@link TurnstileProperties}. Thiếu trường {@code hostname} cũng từ
     * chối: không biết nơi giải thì không chứng minh được gì.
     */
    private void kiemNoiGiai(Object hostname) {
        if (hostname instanceof String h && hostnames.contains(h.toLowerCase(Locale.ROOT))) {
            return;
        }
        // WARN: hoặc có người đang đem token giải ở nơi khác tới, hoặc ta phục vụ trang đăng ký
        // trên một tên miền chưa khai trong OJ_TURNSTILE_HOSTNAMES — cả hai đều cần người nhìn.
        // Tên miền không phải bí mật; token thì có, và không được ghi (bất biến #9).
        log.warn("Turnstile: token giải trên hostname '{}', không thuộc {} — từ chối.", hostname, hostnames);
        throw IdentityException.captchaKhongHopLe();
    }

    /**
     * {@code RestClient.Builder} KHÔNG được auto-config trong ngữ cảnh này — đây là ứng dụng
     * webmvc, không phải webclient, nên không bean nào tồn tại sẵn.
     *
     * <p>Đo thật ngày 2026-09-05: thiếu bean này làm cả context không dựng được, và triệu
     * chứng là {@code NoSuchBeanDefinitionException} ở một lớp IT ngẫu nhiên chứ không phải
     * ở chỗ gây ra.
     *
     * <h2>★ Timeout nằm Ở ĐÂY, và vì sao bean có tên riêng</h2>
     * {@code oj.auth.turnstile.timeout} từng được khai báo, được {@code TurnstileProperties}
     * kiểm lúc boot — và không được dùng ở đâu cả. {@code RestClient.builder()} trần không có
     * timeout nào: Cloudflare treo là luồng đăng ký treo theo, mỗi lượt một luồng, trong khi
     * cấu hình trông như đã có trần 3 giây. Kiểu hỏng tệ nhất — im lặng và trông như đúng.
     *
     * <p>Bản đầu là bean {@code RestClient.Builder} chung kèm {@code @ConditionalOnMissingBean}
     * "để nhường chỗ khi Spring Boot tự cấp". Đặt timeout vào đó thì ngày Boot tự cấp builder
     * (thêm {@code spring-boot-starter-restclient} là đủ), bean này lặng lẽ nhường, và timeout
     * biến mất theo mà không ai hay. Tên riêng + {@code @Qualifier} thì không nhường được: hoặc
     * dùng đúng builder này, hoặc context không dựng được.
     */
    @org.springframework.context.annotation.Configuration
    public static class HttpConfig {

        public static final String BEAN = "turnstileHttp";

        @org.springframework.context.annotation.Bean(BEAN)
        public RestClient.Builder turnstileHttp(AppProperties app) {
            Duration tran = app.auth().turnstile().timeout();
            // Cùng một trần cho hai giai đoạn: mở kết nối, và chờ Cloudflare trả lời.
            var factory = new JdkClientHttpRequestFactory(
                    HttpClient.newBuilder().connectTimeout(tran).build());
            factory.setReadTimeout(tran);
            return RestClient.builder().requestFactory(factory);
        }
    }
}
