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

@SpringBootTest
@org.springframework.context.annotation.Import({DemQuery.class, DiemHenSql.class,
        KhoTestdataTrongBoNho.Dang.class})
public abstract class PostgresIT {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine");

    static {
        REDIS.withExposedPorts(6379);
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
        registry.add("spring.flyway.locations",
                () -> "classpath:db/migration,classpath:db/dev-seed");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("oj.internal.shared-secret", () -> "x".repeat(32));
        registry.add("oj.auth.jwt-secret", () -> "khoa-ky-chi-dung-trong-test-1234567890");
        registry.add("oj.auth.totp-key", () -> "khoa-ma-hoa-totp-chi-dung-trong-test-123");
        registry.add("oj.judge.reaper-interval", () -> "119s");
        registry.add("oj.judge.rabbit.enabled", () -> "false");
        registry.add("OJ_MINIO_ENDPOINT", () -> "http://127.0.0.1:1");
    }

    @Autowired
    @Qualifier("appJdbcClient")
    protected JdbcClient jdbc;

    @Autowired
    protected JwtService jwtService;

    @Autowired
    protected StringRedisTemplate redis;

    @Autowired
    protected dev.oj.platform.settings.SystemSettings congTac;

    private GiaLapDanhTinh phien;

    @BeforeEach
    void dongVaiNguoiDungDev() {
        phien = GiaLapDanhTinh.dongVai(USER_ID, "dev", Role.USER);
    }

    private void adminDaBatHaiLop() {
        jdbc.sql("""
                INSERT INTO user_two_factor (user_id, secret_enc, enabled, confirmed_at)
                VALUES (:id, 'fixture-khong-can-giai-ma', TRUE, now())
                ON CONFLICT (user_id) DO UPDATE SET enabled = TRUE, confirmed_at = now()
                """).param("id", ADMIN_ID).update();
    }

    @AfterEach
    void thoiDongVai() {
        if (phien != null) {
            phien.close();
        }
    }

    protected String bearer(long userId, String handle, Role role) {
        return "Bearer " + jwtService.phat(new CurrentUser(userId, handle, role));
    }

    protected String bearerDev() {
        return bearer(USER_ID, "dev", Role.USER);
    }

    @BeforeEach
    void resetGiuaCacTest() {
        ResetGiuaCacTest.tatCa(jdbc, redis);
        congTac.dat(dev.oj.platform.settings.SystemSettings.NHAN_BAI_NOP, true, null);
        congTac.dat(dev.oj.platform.settings.SystemSettings.REJUDGE, true, null);
        congTac.dat(dev.oj.platform.settings.SystemSettings.AI_REVIEW, false, null);
        adminDaBatHaiLop();
    }

    protected void quenLuotNopVuaRoi(long userId) {
        redis.delete(ResetGiuaCacTest.KHOA_RATE_LIMIT + userId);
    }

    protected void quenLuotDangNhapVuaRoi(long userId) {
        redis.delete("oj:ratelimit:login:" + userId);
    }

    protected static final long PROBLEM_ID = 1L;

    protected static final long USER_ID = 1L;

    protected static final long SETTER_ID = 2L;

    protected static final long ADMIN_ID = 3L;

    protected static final String MAT_KHAU_DEV = "matkhau-dev-123";
}
