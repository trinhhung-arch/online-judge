package dev.oj.it;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import dev.oj.platform.security.Role;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.util.Map;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class HttpIT extends PostgresIT {

    @LocalServerPort
    protected int port;

    protected RestClient http;

    @BeforeEach
    void restClient() {
        http = RestClient.create("http://localhost:" + port);
    }

    protected static final ParameterizedTypeReference<Map<String, Object>> THAN_JSON =
            new ParameterizedTypeReference<>() {
            };

    protected ResponseEntity<Map<String, Object>> login(String dinhDanh, String matKhau) {
        return http.post().uri("/api/v1/auth/login")
                .body(Map.of("dinhDanh", dinhDanh, "password", matKhau))
                .exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
                        .headers(res.getHeaders())
                        .body(res.bodyTo(THAN_JSON)), false);
    }

    private final java.util.Map<String, String> tokenDaLay = new java.util.HashMap<>();

    @org.junit.jupiter.api.BeforeEach
    void quenTokenCu() {
        tokenDaLay.clear();
    }

    protected String tokenCua(String handle) {
        return tokenDaLay.computeIfAbsent(handle, this::layTokenMoi);
    }

    private String layTokenMoi(String handle) {
        if ("admin".equals(handle)) {
            return bearer(ADMIN_ID, "admin", Role.ADMIN).substring("Bearer ".length());
        }
        return (String) login(handle, MAT_KHAU_DEV).getBody().get("accessToken");
    }

    protected ResponseEntity<Map<String, Object>> goi(RestClient.RequestHeadersSpec<?> spec) {
        return spec.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
                .headers(res.getHeaders())
                .body(res.bodyTo(THAN_JSON)), false);
    }
}
