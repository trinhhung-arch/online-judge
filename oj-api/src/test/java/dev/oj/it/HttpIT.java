package dev.oj.it;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Nền cho integration test đi qua <b>một cổng TCP thật</b>, thêm vào nền Postgres + Redis của
 * {@link PostgresIT}.
 *
 * <h2>Vì sao có lớp này thay vì mỗi test tự dựng {@code RestClient}</h2>
 * Vì bốn thứ chỉ hiện ra khi có HTTP thật, và cả bốn đều là kiểu lỗi mà mọi test gọi thẳng
 * use-case đều xanh trong khi hệ thống hỏng: JSON serialize sai một tên trường · một filter
 * đứng nhầm thứ tự trong chuỗi · header {@code Authorization} không được đọc · một kiểu
 * Postgres mà driver không chuyển được. Đã có ít nhất hai lớp test cần đúng bộ đồ nghề này,
 * nên nó nằm ở một chỗ.
 *
 * <p>{@link #login} trả về {@code ResponseEntity} thay vì ném lỗi ở mã 4xx, vì phân nửa số ca
 * kiểm ở đây <b>mong đợi</b> 401, 403 hoặc 429 — và một helper ném lỗi thì không kiểm được
 * chính những ca đó.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class HttpIT extends PostgresIT {

    @LocalServerPort
    protected int port;

    protected RestClient http;

    @BeforeEach
    void restClient() {
        http = RestClient.create("http://localhost:" + port);
    }

    /** Không ném ở 4xx — xem javadoc của class. */
    @SuppressWarnings("rawtypes")
    protected ResponseEntity<Map> login(String dinhDanh, String matKhau) {
        return http.post().uri("/api/v1/auth/login")
                .body(Map.of("dinhDanh", dinhDanh, "password", matKhau))
                .exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
                        .headers(res.getHeaders())
                        .body(res.bodyTo(Map.class)), false);
    }

    /** Access token thật, lấy qua đường đăng nhập thật — BCrypt cost 12 và tất cả. */
    protected String tokenCua(String handle) {
        return (String) login(handle, MAT_KHAU_DEV).getBody().get("accessToken");
    }

    /** Gọi một endpoint và chỉ quan tâm mã trạng thái, không ném ở 4xx. */
    @SuppressWarnings("rawtypes")
    protected ResponseEntity<Map> goi(RestClient.RequestHeadersSpec<?> spec) {
        return spec.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
                .headers(res.getHeaders())
                .body(res.bodyTo(Map.class)), false);
    }
}
