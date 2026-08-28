package dev.oj.it;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;

/**
 * Nền của mọi integration test: <b>một Postgres 16 thật</b>, dùng chung cho cả lớp con.
 *
 * <h2>Vì sao không H2</h2>
 * H2 không có {@code FOR UPDATE SKIP LOCKED}, không có partial index, không có
 * {@code ON CONFLICT ... WHERE}. Đó đúng là ba thứ mà toàn bộ thiết kế hàng đợi dựa vào — một
 * bộ test xanh trên H2 sẽ <b>nói dối về chính những bất biến quan trọng nhất</b>
 * ({@code postgres-design.md} mục 13).
 *
 * <h2>Vì sao vẫn phải bơm thuộc tính bằng tay dù đã có {@code @ServiceConnection}</h2>
 * {@code @ServiceConnection} cấu hình {@code spring.datasource.*}, mà hệ thống này <b>không
 * dùng khối đó</b>: {@code DataSourceConfig} tự dựng hai pool từ {@code oj.datasource.app} và
 * {@code oj.datasource.judge}. Cả hai phải trỏ vào cùng một container, nếu không thì khoá lạc
 * quan và câu ghi verdict nằm ở hai database khác nhau — và test sẽ xanh trong khi hệ thống
 * thật vỡ.
 */
@SpringBootTest
public abstract class PostgresIT {

    /**
     * <b>Singleton container</b> — một Postgres cho cả JVM test, KHÔNG dùng
     * {@code @Testcontainers}/{@code @Container}.
     *
     * <p>Cặp annotation đó gắn vòng đời container vào <b>từng lớp test</b>: container bị dừng
     * ngay sau lớp đầu tiên, và mọi lớp sau nối vào một cổng đã chết
     * ({@code Connection to localhost:xxxxx refused}). Chạy riêng từng lớp thì xanh, chạy cả
     * bộ thì đỏ — loại lỗi tốn nửa ngày để nhìn ra.
     *
     * <p>Không gọi {@code stop()}: Ryuk (container phụ của Testcontainers) dọn khi JVM thoát.
     * Đổi lại, cả bộ IT dùng chung một database — nên {@link #resetHotTables()} bên dưới là
     * bắt buộc, không phải tuỳ chọn.
     */
    @SuppressWarnings("resource")
    // Testcontainers 2.x: lớp này KHÔNG còn generic như bản 1.x mà mọi hướng dẫn còn viết.
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasources(DynamicPropertyRegistry registry) {
        for (String pool : List.of("app", "judge")) {
            registry.add("oj.datasource." + pool + ".jdbc-url", POSTGRES::getJdbcUrl);
            registry.add("oj.datasource." + pool + ".username", POSTGRES::getUsername);
            registry.add("oj.datasource." + pool + ".password", POSTGRES::getPassword);
        }
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        // db/dev-seed cho users.id=1 và đề A-PLUS-B — thiếu nó thì mọi INSERT submissions
        // vỡ khoá ngoại, đúng như trên máy dev.
        registry.add("spring.flyway.locations",
                () -> "classpath:db/migration,classpath:db/dev-seed");
        registry.add("oj.internal.shared-secret", () -> "x".repeat(32));
        // Reaper chạy nền mỗi 15s sẽ chen vào giữa các test đang dựng trạng thái hàng đợi.
        // Đặt sát dưới lease (AppProperties ép reaper-interval < lease) để nó chỉ chạy đúng
        // một lần lúc khởi động rồi im. Test nào cần reaper thì GỌI THẲNG use-case.
        registry.add("oj.judge.reaper-interval", () -> "119s");
    }

    @Autowired
    @Qualifier("appJdbcClient")
    protected JdbcClient jdbc;

    /**
     * Dọn sạch bốn bảng nóng trước mỗi test, đúng thứ tự khoá ngoại.
     *
     * <p>Không dùng {@code @Transactional} + rollback: use-case chạy trên <b>hai transaction
     * manager khác nhau</b> (app và judge), nên một transaction bọc ngoài của test không bao
     * trùm được cả hai — và cái không bị rollback sẽ rò sang test sau.
     */
    @BeforeEach
    void resetHotTables() {
        jdbc.sql("DELETE FROM judge_runs").update();
        jdbc.sql("DELETE FROM judge_queue").update();
        jdbc.sql("DELETE FROM submissions").update();
        jdbc.sql("DELETE FROM source_blobs").update();
    }

    /** Id của đề {@code A-PLUS-B} trong {@code db/dev-seed}. */
    protected static final long PROBLEM_ID = 1L;

    /** Id người dùng mà {@code FixedDevUserProvider} trả về. */
    protected static final long USER_ID = 1L;
}
