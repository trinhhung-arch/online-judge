package dev.oj.platform.config;

import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

/**
 * ★ Profile {@code prod}: mật khẩu hạ tầng còn là giá trị MẶC ĐỊNH thì API từ chối khởi động
 * (rà soát bảo mật 2026-09-24, F7).
 *
 * <h2>Vì sao cần, khi các dịch vụ chỉ nghe loopback</h2>
 * {@code application.yml} để mặc định {@code ojpass} / {@code ojminio123} / Redis không mật khẩu
 * cho máy dev và Testcontainers. Trên host thật, quên MỘT biến lúc dựng lại máy là API chạy
 * bằng mật khẩu ai đọc repo công khai cũng biết — và chạy êm, không một dòng cảnh báo. Trái với
 * lập trường mà phần còn lại của dự án giữ cho secret ({@code OJ_JWT_SECRET},
 * {@code OJ_TURNSTILE_SECRET}…): thiếu thì crash lúc boot. Loopback không cứu được trong kịch
 * bản đáng lo nhất — mã thoát khỏi sandbox chạy ngay trên máy này (F1).
 *
 * <p>Đọc qua {@link Environment}, tức là giá trị SAU khi Spring đã giải {@code ${BIEN:mac-dinh}}
 * — đúng thứ các bean sẽ dùng, không phải biến môi trường thô.
 */
@Component
@Profile("prod")
public class KhongMatKhauMacDinhOProd {

    /** Khoá thuộc tính → giá trị mặc định trong {@code application.yml} / {@code @Value}. */
    static final Map<String, String> MAC_DINH = new TreeMap<>(Map.of(
            "oj.datasource.app.password", "ojpass",
            "oj.datasource.judge.password", "ojpass",
            "spring.flyway.password", "ojpass",
            "spring.rabbitmq.password", "ojpass",
            "OJ_MINIO_SECRET_KEY", "ojminio123",      // @Value mặc định trong MinioTestdataStore
            "spring.data.redis.password", ""));        // rỗng = Redis không khoá

    public KhongMatKhauMacDinhOProd(Environment env) {
        kiem(env::getProperty);
    }

    static void kiem(UnaryOperator<String> doc) {
        List<String> yeu = new ArrayList<>();
        MAC_DINH.forEach((khoa, macDinh) -> {
            String v = doc.apply(khoa);
            if (v == null || v.isBlank() || v.equals(macDinh)) {
                yeu.add(khoa);
            }
        });
        if (!yeu.isEmpty()) {
            throw new IllegalStateException("Profile prod mà mật khẩu hạ tầng còn là mặc định (hoặc rỗng): "
                    + yeu + ". Đặt biến môi trường tương ứng trong .env — xem .env.example. Không có "
                    + "đường chạy tiếp: mật khẩu mặc định nằm trong repo công khai.");
        }
    }
}
