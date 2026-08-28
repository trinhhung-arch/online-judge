package dev.oj.platform.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.List;

/**
 * Hiện thực M1 của {@link CurrentUserProvider}: luôn trả người dùng đã seed sẵn, id = 1.
 *
 * <p>Nó tồn tại để M1 chứng minh được vòng {@code accept != process}, reaper, khoá lạc quan và
 * "2 worker không chấm trùng" mà không phải chờ tới tuần 7 mới có đăng nhập.
 *
 * <h2>⚠️ Đây là một cửa hậu, và nó được đối xử như một cửa hậu</h2>
 * Một bản {@code CurrentUserProvider} luôn trả ADMIN mà lọt lên host là mất trắng: bất kỳ ai
 * mở được trang cũng là ADMIN, xem được testdata mọi đề, xem được {@code audit_log}. Với một
 * hệ thống mà thứ nó bán là <b>sự công bằng</b>, đó không phải sự cố mà là kết thúc.
 *
 * <p>Nên class này có <b>hai lớp chặn, và cả hai đều chủ động</b>:
 *
 * <ol>
 *   <li>Bean được đăng ký qua {@link DevSecurityConfig} với {@code @ConditionalOnMissingBean},
 *       nên ngày {@code JwtCurrentUserProvider} ra đời (Bước 4.5) thì bean này <b>tự biến mất</b>.
 *       <b>Chú ý chỗ đặt annotation:</b> {@code @ConditionalOnMissingBean} chỉ đáng tin trên một
 *       phương thức {@code @Bean}. Đặt nó thẳng lên một class {@code @Component} — như bản đầu
 *       của file này từng làm — là dựa vào thứ tự quét component, thứ Spring không bảo đảm:
 *       tới M4 nó có thể im lặng giữ lại cửa hậu, hoặc ném
 *       {@code NoUniqueBeanDefinitionException}. Cả hai đều tệ, và cái đầu tệ hơn nhiều.</li>
 *   <li>Constructor <b>ném lỗi lúc boot</b> nếu profile là {@code prod}. Cùng tinh thần với
 *       {@code EnvVarStartupCheck}: thà không khởi động được còn hơn khởi động sai. Một cửa hậu
 *       phải làm sập tiến trình, không phải in ra một dòng WARN mà không ai đọc. Đây là lớp
 *       chặn <b>không</b> phụ thuộc vào thứ tự khởi tạo bean nào cả.</li>
 * </ol>
 *
 * <p>Vai trò trả về là {@link Role#USER}, <b>không phải ADMIN</b> — cố ý. M1 chỉ cần nộp bài,
 * và một cửa hậu ở mức USER thì thiệt hại nếu lọt ra vẫn nhỏ hơn hẳn.
 */
public class FixedDevUserProvider implements CurrentUserProvider {

    private static final Logger log = LoggerFactory.getLogger(FixedDevUserProvider.class);

    /**
     * Khớp người dùng seed trong {@code db/dev-seed/R__seed_du_lieu_dev.sql} — thư mục chỉ
     * được nạp khi profile {@code dev} bật ({@code application-dev.yml}).
     *
     * <p><b>Không phải {@code R__seed_du_lieu_tham_chieu.sql}</b>: file đó là dữ liệu tham
     * chiếu chạy ở mọi môi trường, và một tài khoản không mật khẩu tên 'dev' không có việc gì
     * ở đó. {@code submissions.user_id} có khoá ngoại, nên thiếu hàng này thì lần nộp bài đầu
     * tiên vỡ ngay — đó là cách bạn biết seed chưa chạy.
     */
    private static final CurrentUser DEV_USER = new CurrentUser(1L, "dev", Role.USER);

    private static final List<String> FORBIDDEN_PROFILES = List.of("prod", "production", "host");

    public FixedDevUserProvider(Environment environment) {
        List<String> active = Arrays.asList(environment.getActiveProfiles());
        for (String profile : FORBIDDEN_PROFILES) {
            if (active.contains(profile)) {
                throw new IllegalStateException("""
                        FixedDevUserProvider đang hoạt động trong profile '%s'.

                        Đây là cửa hậu xác thực của M1: nó bỏ qua đăng nhập và trả về người dùng
                        seed id=1 cho MỌI request. Chạy nó trên host nghĩa là bất kỳ ai mở được
                        trang cũng đăng nhập được thành người khác.

                        Tới M4 thì JwtCurrentUserProvider phải tồn tại, và bean này tự biến mất
                        nhờ @ConditionalOnMissingBean. Nếu bạn thấy thông báo này, nghĩa là
                        JwtCurrentUserProvider chưa được đăng ký.
                        """.formatted(profile));
            }
        }
        log.warn("""

                ┌──────────────────────────────────────────────────────────────┐
                │  XÁC THỰC ĐANG TẮT — FixedDevUserProvider (chỉ dành cho M1)  │
                │  Mọi request chạy dưới danh nghĩa users.id=1 ('dev', USER).  │
                │  Thay bằng JwtCurrentUserProvider ở Bước 4.5.                │
                └──────────────────────────────────────────────────────────────┘
                """);
    }

    @Override
    public CurrentUser current() {
        return DEV_USER;
    }
}
