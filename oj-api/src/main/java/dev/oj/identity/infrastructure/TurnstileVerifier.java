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

@Component
public class TurnstileVerifier implements CaptchaVerifier {

    private static final Logger log = LoggerFactory.getLogger(TurnstileVerifier.class);

    private final TurnstileProperties properties;
    private final RestClient http;

    private final Set<String> hostnames;

    public TurnstileVerifier(AppProperties app, @Qualifier(HttpConfig.BEAN) RestClient.Builder builder) {
        this.properties = app.auth().turnstile();
        this.http = builder.build();
        this.hostnames = properties.hostnames().stream()
                .map(h -> h.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        if (!properties.enabled()) {
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
            log.warn("Không hỏi được Turnstile ({}). Từ chối lượt đăng ký này — xem javadoc "
                    + "để biết vì sao không cho qua.", e.toString());
            throw IdentityException.captchaKhongHopLe();
        }

        if (traLoi == null || !Boolean.TRUE.equals(traLoi.get("success"))) {
            log.debug("Turnstile từ chối: {}", traLoi == null ? "không có thân" : traLoi.get("error-codes"));
            throw IdentityException.captchaKhongHopLe();
        }
        kiemNoiGiai(traLoi.get("hostname"));
    }

    private void kiemNoiGiai(Object hostname) {
        if (hostname instanceof String h && hostnames.contains(h.toLowerCase(Locale.ROOT))) {
            return;
        }
        log.warn("Turnstile: token giải trên hostname '{}', không thuộc {} — từ chối.", hostname, hostnames);
        throw IdentityException.captchaKhongHopLe();
    }

    @org.springframework.context.annotation.Configuration
    public static class HttpConfig {

        public static final String BEAN = "turnstileHttp";

        @org.springframework.context.annotation.Bean(BEAN)
        public RestClient.Builder turnstileHttp(AppProperties app) {
            Duration tran = app.auth().turnstile().timeout();
            var factory = new JdkClientHttpRequestFactory(
                    HttpClient.newBuilder().connectTimeout(tran).build());
            factory.setReadTimeout(tran);
            return RestClient.builder().requestFactory(factory);
        }
    }
}
