package dev.oj.identity.infrastructure;

import com.sun.net.httpserver.HttpServer;
import dev.oj.identity.domain.IdentityException;
import dev.oj.platform.config.AppPropertiesGia;
import dev.oj.platform.config.TurnstileProperties;
import dev.oj.platform.error.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * ★ Widget ở trình duyệt không chứng minh gì cả — nó chỉ sinh ra một chuỗi. Thứ duy nhất
 * bảo vệ cửa đăng ký là lượt hỏi lại Cloudflare ở đây.
 */
class TurnstileVerifierTest {

    private static final String URL = "https://vi-du.test/siteverify";

    /** Máy chủ Cloudflare giả — test này KHÔNG được gọi ra internet. */
    private MockRestServiceServer cloudflareGia;

    private TurnstileVerifier verifier(boolean bat) {
        var builder = RestClient.builder();
        cloudflareGia = MockRestServiceServer.bindTo(builder).build();
        return new TurnstileVerifier(
                AppPropertiesGia.voiTurnstile(new TurnstileProperties(
                        bat, bat ? "site" : "", bat ? "secret" : "", URL, Duration.ofSeconds(3))),
                builder);
    }

    @Test
    @DisplayName("TẮT thì cho qua và KHÔNG gọi ra ngoài — máy dev không cần khoá Cloudflare")
    void tat_thi_cho_qua() {
        var v = verifier(false);

        assertThatCode(() -> v.kiem(null, "203.0.113.1")).doesNotThrowAnyException();
        cloudflareGia.verify();   // không có lời gọi nào được mong đợi, và không có lời gọi nào xảy ra
    }

    @Test
    @DisplayName("BẬT mà thiếu token → từ chối, và không tốn một lượt gọi ra ngoài")
    void thieu_token_thi_tu_choi_ngay() {
        var v = verifier(true);

        assertThatThrownBy(() -> v.kiem("  ", "203.0.113.1"))
                .isInstanceOf(IdentityException.class)
                .hasFieldOrPropertyWithValue("code", "identity.captcha_khong_hop_le");
        cloudflareGia.verify();
    }

    @Test
    @DisplayName("Cloudflare trả success=true thì cho qua")
    void thanh_cong() {
        var v = verifier(true);
        cloudflareGia.expect(requestTo(URL))
                .andRespond(withSuccess("{\"success\":true}", MediaType.APPLICATION_JSON));

        assertThatCode(() -> v.kiem("token-that", "203.0.113.1")).doesNotThrowAnyException();
        cloudflareGia.verify();
    }

    @Test
    @DisplayName("★ success=false thì TỪ CHỐI — không được coi 'có trả lời' là 'hợp lệ'")
    void that_bai() {
        var v = verifier(true);
        cloudflareGia.expect(requestTo(URL))
                .andRespond(withSuccess(
                        "{\"success\":false,\"error-codes\":[\"invalid-input-response\"]}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> v.kiem("token-gia", "203.0.113.1"))
                .isInstanceOf(IdentityException.class)
                .hasFieldOrPropertyWithValue("kind", DomainException.Kind.INVALID);
    }

    @Test
    @DisplayName("★ Cloudflare không trả lời thì TỪ CHỐI, không cho qua")
    void hong_thi_tu_choi() {
        var v = verifier(true);
        cloudflareGia.expect(requestTo(URL)).andRespond(withServerError());

        assertThatThrownBy(() -> v.kiem("token", "203.0.113.1"))
                .as("cho qua nghĩa là một bot chỉ cần làm nghẽn đường ra internet là vô "
                        + "hiệu hoá cả hàng rào")
                .isInstanceOf(IdentityException.class)
                .hasFieldOrPropertyWithValue("code", "identity.captcha_khong_hop_le");
    }

    /**
     * ★ Cloudflare không trả lời mà cũng không từ chối — nó chỉ im. Đây là ca {@code
     * hong_thi_tu_choi} không bắt được, vì máy chủ giả trả lỗi NGAY.
     *
     * <p>Chạy trên một máy chủ HTTP thật cục bộ (JDK, không thêm dependency), và đi qua
     * CHÍNH bean {@link TurnstileVerifier.HttpConfig#turnstileHttp} mà ứng dụng dùng: timeout
     * phải đến từ cấu hình thật, không phải từ một builder test dựng riêng.
     */
    @Test
    @DisplayName("★ Cloudflare TREO → từ chối sau đúng trần timeout, không giữ luồng đăng ký")
    void treo_thi_cat_theo_timeout() throws Exception {
        var tha = new CountDownLatch(1);
        HttpServer cloudflareTreo = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        cloudflareTreo.createContext("/siteverify", trao -> {
            try {
                tha.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            trao.close();
        });
        cloudflareTreo.start();
        try {
            var props = AppPropertiesGia.voiTurnstile(new TurnstileProperties(true, "site", "secret",
                    "http://127.0.0.1:" + cloudflareTreo.getAddress().getPort() + "/siteverify",
                    Duration.ofMillis(300)));
            var v = new TurnstileVerifier(props, new TurnstileVerifier.HttpConfig().turnstileHttp(props));

            long batDau = System.nanoTime();
            assertThatThrownBy(() -> v.kiem("token", "203.0.113.1"))
                    .hasFieldOrPropertyWithValue("code", "identity.captcha_khong_hop_le");
            assertThat(Duration.ofNanos(System.nanoTime() - batDau))
                    .as("trần 300ms mà chờ lâu hơn nhiều nghĩa là timeout không được áp")
                    .isLessThan(Duration.ofSeconds(3));
        } finally {
            tha.countDown();
            cloudflareTreo.stop(0);
        }
    }
}
