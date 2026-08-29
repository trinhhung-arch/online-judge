package dev.oj.it;

import dev.oj.platform.security.CurrentUserProvider.CurrentUser;
import dev.oj.platform.security.GiaLapDanhTinh;
import dev.oj.platform.security.JwtService;
import dev.oj.platform.security.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
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
        // Bước 4.5: không có mặc định trong application.yml, cố ý. Thiếu dòng này thì mọi IT
        // đỏ ngay lúc dựng context, với đúng thông báo mà một host cấu hình thiếu sẽ thấy.
        registry.add("oj.auth.jwt-secret", () -> "khoa-ky-chi-dung-trong-test-1234567890");
        // Reaper chạy nền mỗi 15s sẽ chen vào giữa các test đang dựng trạng thái hàng đợi.
        // Đặt sát dưới lease (AppProperties ép reaper-interval < lease) để nó chỉ chạy đúng
        // một lần lúc khởi động rồi im. Test nào cần reaper thì GỌI THẲNG use-case.
        registry.add("oj.judge.reaper-interval", () -> "119s");
    }

    @Autowired
    @Qualifier("appJdbcClient")
    protected JdbcClient jdbc;

    @Autowired
    protected JwtService jwtService;

    @Autowired
    protected StringRedisTemplate redis;

    private GiaLapDanhTinh phien;

    /**
     * ★ Mỗi test chạy dưới danh nghĩa {@code users.id = 1} — thay cho vai trò mà
     * {@code FixedDevUserProvider} từng đảm nhiệm tới hết M3.
     *
     * <h2>Vì sao cần dòng này từ Bước 4.6 trở đi</h2>
     * {@code @RequiresRole} được ép bằng một AOP advisor, và advisor đó chạy trên <b>bean của
     * Spring</b>. Test gọi thẳng {@code submitSolution.thucHien(...)} là gọi qua proxy, nên nó
     * cũng bị kiểm quyền như một request thật — mà một test thì không có request nào, không có
     * header {@code Authorization} nào, và {@code JwtAuthFilter} chưa từng chạy.
     *
     * <p>Không có dòng này thì mọi IT đỏ với {@code auth.chua_dang_nhap}. Điều đó <i>đúng</i>:
     * nó chứng minh advisor thật sự đang chặn. Nhưng nó chứng minh ở sai chỗ — việc chứng
     * minh thuộc về {@code RequiresRoleIT}, còn ở đây thì nó chỉ che mất thứ mỗi test muốn kiểm.
     *
     * <p>Test cần một vai trò khác (SETTER, ADMIN, hoặc khách) thì tự mở
     * {@link GiaLapDanhTinh} lồng bên trong; phiên trong ra ghi đè phiên ngoài và trả lại
     * trạng thái rỗng khi đóng.
     */
    @BeforeEach
    void dongVaiNguoiDungDev() {
        phien = GiaLapDanhTinh.dongVai(USER_ID, "dev", Role.USER);
    }

    @AfterEach
    void thoiDongVai() {
        if (phien != null) {
            phien.close();
        }
    }

    /**
     * Access token thật cho một IT gọi qua HTTP.
     *
     * <p>Ký bằng chính {@code JwtService} của ứng dụng chứ không dựng một token bằng tay: nếu
     * định dạng token đổi thì test đổi theo mà không phải sửa, và quan trọng hơn — một token
     * dựng tay có thể vô tình <i>đúng</i> trong khi hàm phát token thật đang sai.
     */
    protected String bearer(long userId, String handle, Role role) {
        return "Bearer " + jwtService.phat(new CurrentUser(userId, handle, role));
    }

    /** Token của {@code users.id = 1} — người dùng mặc định của phần lớn IT. */
    protected String bearerDev() {
        return bearer(USER_ID, "dev", Role.USER);
    }

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
    /**
     * Trả Postgres và Redis về đúng trạng thái sau khi seed, trước mỗi test.
     * Chi tiết và lý do ở {@link ResetGiuaCacTest} — tách ra vì trần 300 dòng
     * ({@code CLAUDE.md} mục 7).
     */
    @BeforeEach
    void resetGiuaCacTest() {
        ResetGiuaCacTest.tatCa(jdbc, redis);
    }

    /**
     * Xoá khoá rate limit của một người, để test nộp được bài tiếp theo ngay lập tức.
     *
     * <h2>Vì sao là helper này chứ không phải {@code @TestPropertySource} hạ cửa sổ xuống</h2>
     * Vì <b>mỗi bộ thuộc tính khác nhau là một Spring context khác</b>, và mỗi context dựng
     * hai pool Hikari: 20 connection cho {@code app} cộng 6 cho {@code judge}. Postgres mặc
     * định cho 100. Thêm một context là thêm 26, và triệu chứng không phải "cấu hình sai" mà
     * là <b>{@code FATAL: sorry, too many clients already}</b> ở một lớp test ngẫu nhiên —
     * thường là lớp chạy sau cùng, tức là không phải lớp gây ra. Đây là lỗi đã gặp thật khi
     * viết Bước 4.7.
     *
     * <p>Helper này không đổi cấu hình gì cả: chốt rate limit <b>vẫn chạy</b> với con số 10
     * giây thật và vẫn nằm trong thời gian đo được của {@code SubmitLatencyIT}. Nó chỉ xoá
     * dấu vết của lượt nộp trước — đúng như thể mười giây đã trôi qua.
     */
    protected void quenLuotNopVuaRoi(long userId) {
        redis.delete(ResetGiuaCacTest.KHOA_RATE_LIMIT + userId);
    }

    /** Id của đề {@code A-PLUS-B} trong {@code db/dev-seed}. */
    protected static final long PROBLEM_ID = 1L;

    /** {@code dev} — USER. Người dùng mặc định của mọi IT, xem {@link #dongVaiNguoiDungDev()}. */
    protected static final long USER_ID = 1L;

    /** {@code setter} — SETTER, và là {@code owner_id} của đề A-PLUS-B. */
    protected static final long SETTER_ID = 2L;

    /** {@code admin} — ADMIN. */
    protected static final long ADMIN_ID = 3L;

    /** Mật khẩu chung của ba tài khoản seed. Khớp băm trong {@code R__seed_du_lieu_dev.sql}. */
    protected static final String MAT_KHAU_DEV = "matkhau-dev-123";
}
