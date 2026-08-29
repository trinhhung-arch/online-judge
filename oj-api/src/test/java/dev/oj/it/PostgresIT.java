package dev.oj.it;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
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

    /**
     * ★ Redis THẬT, không phải bản giả trong bộ nhớ.
     *
     * <p>Cả đường realtime của M3 nằm trên pub/sub: {@code RecordJudgeResultUseCase} publish
     * sau commit, {@code RedisMessageListenerContainer} nhận, {@code SseEmitter} đẩy đi. Một
     * bus giả trong cùng JVM sẽ xanh cho <b>mọi</b> hiện thực — kể cả một hiện thực giữ danh
     * sách kết nối trong bộ nhớ, tức là đúng thứ ADR 011 nói phải tránh. Chỉ container thật
     * mới chứng minh được thông điệp đi qua một tiến trình khác rồi quay lại.
     *
     * <p>{@code redis:7-alpine} khởi động khoảng một giây, một lần cho cả JVM.
     */
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
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
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
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
     * Trả cơ sở dữ liệu về đúng trạng thái sau khi seed, trước mỗi test.
     *
     * <p>Không dùng {@code @Transactional} + rollback: use-case chạy trên <b>hai transaction
     * manager khác nhau</b> (app và judge), nên một transaction bọc ngoài của test không bao
     * trùm được cả hai — và cái không bị rollback sẽ rò sang test sau.
     *
     * <h2>Vì sao có cả {@code judge_hosts} và {@code host_benchmarks} ở đây</h2>
     * Vì {@code /internal/judge/benchmark} (M2) <b>sửa</b> {@code judge_hosts.host_factor} và
     * {@code last_seen_at}, rồi thêm một dòng vào {@code host_benchmarks}. Không hoàn nguyên
     * thì test chạy trước để lại {@code host_factor = 1.250} cho test chạy sau — và cả bộ IT
     * dùng chung <b>một</b> container Postgres, nên "test chạy sau" nghĩa là mọi test còn lại.
     *
     * <p>Kiểu hỏng của nó là kiểu tệ nhất: nó phụ thuộc <b>thứ tự chạy</b>, mà thứ tự mặc
     * định của Failsafe là thứ tự thư mục — khác nhau giữa máy dev và runner CI. Kết quả là
     * xanh ở đây, đỏ ở kia, và không ai tái hiện được. Xem thêm {@code <runOrder>} trong
     * {@code oj-api/pom.xml}.
     *
     * <p>{@code 1.000} là giá trị của máy chấm chuẩn trong
     * {@code R__seed_du_lieu_tham_chieu.sql}. Nó là hằng số theo định nghĩa: mọi giới hạn
     * thời gian của đề đều quy chiếu về máy đó ({@code nfrplan.md} 9.1).
     */
    @BeforeEach
    void resetHotTables() {
        jdbc.sql("DELETE FROM judge_runs").update();
        jdbc.sql("DELETE FROM judge_queue").update();
        jdbc.sql("DELETE FROM submissions").update();
        jdbc.sql("DELETE FROM source_blobs").update();

        jdbc.sql("DELETE FROM host_benchmarks").update();
        jdbc.sql("UPDATE judge_hosts SET host_factor = 1.000, last_seen_at = NULL").update();

        // Cùng lý do: SubmissionFeedbackIT đổi feedback_level để kiểm bộ lọc, và một mức
        // NONE sót lại sẽ làm mọi test sau đó không thấy failed_test_ordinal — hỏng theo
        // thứ tự chạy, tức là xanh ở máy này đỏ ở máy kia.
        jdbc.sql("UPDATE problems SET feedback_level = 'TEST_INDEX', "
                + "scoring_mode = 'ALL_OR_NOTHING'").update();

        // Nhóm test: gỡ tham chiếu từ `testcases` TRƯỚC rồi mới xoá `subtasks` — khoá ngoại
        // đi theo chiều đó. `judge_run_subtasks` tự biến mất theo `judge_runs` (ON DELETE
        // CASCADE), nên nó không cần một dòng riêng ở đây.
        jdbc.sql("UPDATE testcases SET subtask_id = NULL WHERE subtask_id IS NOT NULL").update();
        jdbc.sql("DELETE FROM subtasks").update();
    }

    /** Id của đề {@code A-PLUS-B} trong {@code db/dev-seed}. */
    protected static final long PROBLEM_ID = 1L;

    /** Id người dùng mà {@code FixedDevUserProvider} trả về. */
    protected static final long USER_ID = 1L;
}
