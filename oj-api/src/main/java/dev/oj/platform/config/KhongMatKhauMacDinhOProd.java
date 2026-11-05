package dev.oj.platform.config;

import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

@Component
@Profile("prod")
public class KhongMatKhauMacDinhOProd {

    static final Map<String, String> MAC_DINH = new TreeMap<>(Map.of(
            "oj.datasource.app.password", "ojpass",
            "oj.datasource.judge.password", "ojpass",
            "spring.flyway.password", "ojpass",
            "spring.rabbitmq.password", "ojpass",
            "OJ_MINIO_SECRET_KEY", "ojminio123",
            "spring.data.redis.password", ""));

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
