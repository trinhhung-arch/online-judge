package dev.oj.platform.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** F7 · rà soát 2026-09-24 — mật khẩu mặc định ở profile prod thì không khởi động. */
class KhongMatKhauMacDinhOProdTest {

    private static Map<String, String> manh() {
        var m = new HashMap<String, String>();
        KhongMatKhauMacDinhOProd.MAC_DINH.keySet().forEach(k -> m.put(k, "x".repeat(48)));
        return m;
    }

    @Test
    @DisplayName("mọi mật khẩu đã đặt, khác mặc định → khởi động bình thường")
    void du_manh_thi_qua() {
        var m = manh();
        assertThatCode(() -> KhongMatKhauMacDinhOProd.kiem(m::get)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★ một mật khẩu còn 'ojpass' → từ chối, và NÓI RA khoá nào")
    void con_mac_dinh_thi_crash() {
        var m = manh();
        m.put("spring.rabbitmq.password", "ojpass");

        assertThatThrownBy(() -> KhongMatKhauMacDinhOProd.kiem(m::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.rabbitmq.password")
                .hasMessageNotContaining("oj.datasource.app.password");
    }

    @Test
    @DisplayName("★ Redis rỗng, hoặc MinIO không đặt (lùi về ojminio123) → từ chối")
    void rong_hoac_khong_dat_thi_crash() {
        var m = manh();
        m.put("spring.data.redis.password", "");
        m.remove("OJ_MINIO_SECRET_KEY");

        assertThatThrownBy(() -> KhongMatKhauMacDinhOProd.kiem(m::get))
                .hasMessageContaining("spring.data.redis.password")
                .hasMessageContaining("OJ_MINIO_SECRET_KEY");
    }

    @Test
    @DisplayName("chỉ chạy ở profile prod — máy dev và Testcontainers dùng mặc định là đúng")
    void chi_o_prod() {
        assertThat(KhongMatKhauMacDinhOProd.class.getAnnotation(Profile.class).value())
                .containsExactly("prod");
    }
}
