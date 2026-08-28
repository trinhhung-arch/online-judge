package dev.oj.platform.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Đăng ký {@link FixedDevUserProvider} — và tồn tại <b>chỉ vì một chi tiết của Spring</b>.
 *
 * <p>{@code @ConditionalOnMissingBean} chỉ được bảo đảm khi nó nằm trên một phương thức
 * {@code @Bean}. Trên một class {@code @Component} thì điều kiện được lượng giá trong lúc quét
 * component, mà thứ tự quét thì không xác định — nên ở M4, khi {@code JwtCurrentUserProvider}
 * xuất hiện, kết quả phụ thuộc may rủi.
 *
 * <p>Hai kết cục có thể xảy ra, và cái đầu mới đáng sợ:
 * <ul>
 *   <li>cửa hậu M1 <b>được giữ lại im lặng</b> và mọi request chạy dưới danh nghĩa
 *       {@code users.id=1} — trên một hệ thống mà thứ nó bán là sự công bằng;</li>
 *   <li>hoặc {@code NoUniqueBeanDefinitionException} lúc khởi động — ồn ào, nên đỡ hơn.</li>
 * </ul>
 *
 * <p>Đặt ở đây thì điều kiện được lượng giá <b>sau</b> khi mọi bean quét-được đã đăng ký, và
 * "bean này tự biến mất ở M4" trở thành một bảo đảm thay vì một hy vọng.
 *
 * <p><b>Ở Bước 4.5:</b> viết {@code JwtCurrentUserProvider} như một {@code @Component} bình
 * thường. Không cần đụng vào file này, và cũng không cần nhớ xoá nó — nhưng xoá thì tốt hơn.
 */
@Configuration
public class DevSecurityConfig {

    @Bean
    @ConditionalOnMissingBean(CurrentUserProvider.class)
    public CurrentUserProvider fixedDevUserProvider(Environment environment) {
        return new FixedDevUserProvider(environment);
    }
}
