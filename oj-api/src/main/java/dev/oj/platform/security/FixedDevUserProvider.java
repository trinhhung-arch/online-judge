package dev.oj.platform.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

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
 *   <li>{@code @ConditionalOnMissingBean} — ngày {@code JwtCurrentUserProvider} ra đời (Bước 4.5),
 *       bean này <b>tự biến mất</b>. Không ai phải nhớ xoá nó, và không có giai đoạn hai bean
 *       cùng tồn tại rồi Spring chọn nhầm.</li>
 *   <li>Constructor <b>ném lỗi lúc boot</b> nếu profile là {@code prod}. Cùng tinh thần với
 *       {@code EnvVarStartupCheck}: thà không khởi động được còn hơn khởi động sai. Một cửa hậu
 *       phải làm sập tiến trình, không phải in ra một dòng WARN mà không ai đọc.</li>
 * </ol>
 *
 * <p>Vai trò trả về là {@link Role#USER}, <b>không phải ADMIN</b> — cố ý. M1 chỉ cần nộp bài,
 * và một cửa hậu ở mức USER thì thiệt hại nếu lọt ra vẫn nhỏ hơn hẳn.
 */
@Component
@ConditionalOnMissingBean(CurrentUserProvider.class)
public class FixedDevUserProvider implements CurrentUserProvider {

    private static final Logger log = LoggerFactory.getLogger(FixedDevUserProvider.class);

    /** Khớp với người dùng seed trong {@code R__seed_du_lieu_tham_chieu.sql} / smoke test. */
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
