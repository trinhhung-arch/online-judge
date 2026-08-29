package dev.oj.it;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Đưa Postgres và Redis về trạng thái ngay sau khi seed, trước <b>mỗi</b> test.
 *
 * <h2>Vì sao không dùng {@code @Transactional} + rollback</h2>
 * Use-case chạy trên <b>hai transaction manager khác nhau</b> ({@code app} và {@code judge}),
 * nên một transaction bọc ngoài của test không bao trùm được cả hai — và cái không bị rollback
 * sẽ rò sang test sau. Thêm nữa, phần trạng thái nằm ở Redis thì transaction nào cũng không
 * với tới.
 *
 * <h2>★ Ba lần cùng một bài học trong dự án này</h2>
 * <ol>
 *   <li>M2 — {@code judge_hosts.host_factor} bị một test benchmark đổi thành 1.250 và mọi test
 *       sau đó tính sai giới hạn thời gian.</li>
 *   <li>M3 — {@code problems.feedback_level} bị đặt {@code NONE} và mọi test sau không thấy
 *       {@code failed_test_ordinal}.</li>
 *   <li>M4 — khoá rate limit trong Redis sống 10 giây, <b>không biết gì về ranh giới giữa các
 *       test</b>: test A nộp bài lúc 0,0s và test B bị 429 lúc 0,4s vì một lượt nộp nó không
 *       hề thực hiện.</li>
 * </ol>
 * Cả ba đều cùng một kiểu hỏng, và là kiểu tệ nhất: <b>phụ thuộc thứ tự chạy</b>. Xanh khi
 * chạy một lớp, đỏ khi chạy cả bộ, và đỏ khác nhau giữa máy dev với runner CI. Xem
 * {@code <runOrder>alphabetical</runOrder>} trong {@code oj-api/pom.xml}.
 *
 * <p>Quy tắc rút ra: <b>thêm một bảng hay một khoá mà use-case GHI vào thì thêm một dòng ở
 * đây</b>, cùng lúc, không để lần sau.
 */
final class ResetGiuaCacTest {

    /** Khớp {@code RedisSubmissionRateLimiter.KHOA_PREFIX}. */
    static final String KHOA_RATE_LIMIT = "oj:ratelimit:submit:";

    /** Băm BCrypt cost 12 của {@code matkhau-dev-123} — khớp {@code R__seed_du_lieu_dev.sql}. */
    private static final String BAM_MAT_KHAU_DEV =
            "$2a$12$nbmWQ37swiou9I6Nm3N1YuQcTPBYnK/nSbXxt2M0FZc9GkUuldEoS";

    private ResetGiuaCacTest() {
    }

    static void tatCa(JdbcClient jdbc, StringRedisTemplate redis) {
        jobNen(jdbc);
        duongCham(jdbc);
        danhTinh(jdbc);
        rateLimit(redis);
    }

    /**
     * {@code judge_hosts} và {@code host_benchmarks} có mặt ở đây vì
     * {@code /internal/judge/benchmark} <b>sửa</b> chúng. {@code 1.000} là giá trị của máy chấm
     * chuẩn trong {@code R__seed_du_lieu_tham_chieu.sql} — một hằng số theo định nghĩa
     * ({@code nfrplan.md} 9.1).
     */
    private static void duongCham(JdbcClient jdbc) {
        jdbc.sql("DELETE FROM judge_runs").update();
        jdbc.sql("DELETE FROM judge_queue").update();
        jdbc.sql("DELETE FROM submissions").update();
        jdbc.sql("DELETE FROM source_blobs").update();

        jdbc.sql("DELETE FROM host_benchmarks").update();
        jdbc.sql("UPDATE judge_hosts SET host_factor = 1.000, last_seen_at = NULL").update();
        jdbc.sql("UPDATE problems SET feedback_level = 'TEST_INDEX', "
                + "scoring_mode = 'ALL_OR_NOTHING'").update();

        // Gỡ tham chiếu từ `testcases` TRƯỚC rồi mới xoá `subtasks` — khoá ngoại đi theo chiều
        // đó. `judge_run_subtasks` tự biến mất theo `judge_runs` (ON DELETE CASCADE).
        jdbc.sql("UPDATE testcases SET subtask_id = NULL WHERE subtask_id IS NOT NULL").update();
        jdbc.sql("DELETE FROM subtasks").update();

        // Đề do test tạo (Bước 4.9). Phải sau khi xoá `submissions` — khoá ngoại đi theo
        // chiều đó và KHÔNG có ON DELETE CASCADE. `testcases` và `testdata_versions` thì có,
        // nên chúng tự biến mất theo.
        jdbc.sql("DELETE FROM problems WHERE id > 1").update();
    }

    /**
     * Bốn bảng của V5, cộng ba tài khoản seed.
     *
     * <h2>Vì sao {@code users} cũng phải hoàn nguyên</h2>
     * {@code AnonymizeAccountUseCase} <b>sửa</b> nó: xoá email, xoá băm mật khẩu, đổi tên hiển
     * thị. Một test ẩn danh hoá {@code setter} sẽ để lại một tài khoản không đăng nhập được
     * cho mọi test chạy sau.
     *
     * <h2>Vì sao xoá được {@code audit_log} dù nó là append-only</h2>
     * Tính append-only được ép bằng <b>phân quyền</b> ({@code REVOKE DELETE ... FROM oj_app}),
     * và phần GRANT đó nằm ở V9 (M6). Trong test, Flyway chạy bằng vai trò sở hữu schema nên
     * lệnh xoá vẫn thực hiện được. Khi V9 được kích hoạt, dòng này sẽ đỏ — và đó là lời nhắc
     * đúng lúc rằng test cần một vai trò riêng, chứ không phải rằng V9 sai.
     */
    private static void danhTinh(JdbcClient jdbc) {
        jdbc.sql("DELETE FROM refresh_tokens").update();
        jdbc.sql("DELETE FROM login_attempts").update();
        jdbc.sql("DELETE FROM login_lockouts").update();
        jdbc.sql("DELETE FROM audit_log").update();
        jdbc.sql("DELETE FROM users WHERE id > 3").update();
        jdbc.sql("""
                UPDATE users SET
                    email = CASE id WHEN 1 THEN 'dev@oj.test'
                                    WHEN 2 THEN 'setter@oj.test'
                                    ELSE 'admin@oj.test' END,
                    display_name = CASE id WHEN 1 THEN 'Người dùng dev'
                                           WHEN 2 THEN 'Người ra đề'
                                           ELSE 'Quản trị viên' END,
                    role = CASE id WHEN 1 THEN 'USER' WHEN 2 THEN 'SETTER' ELSE 'ADMIN' END,
                    status = 'ACTIVE',
                    preferred_language_id = NULL,
                    password_hash = :bam
                 WHERE id <= 3
                """).param("bam", BAM_MAT_KHAU_DEV).update();
    }

    /**
     * Bảng của V6. {@code job_events} tự biến mất theo {@code jobs}
     * ({@code ON DELETE CASCADE}), nhưng {@code testdata_versions} thì không — nó thuộc
     * {@code problems}, và một phiên bản testdata sót lại sẽ làm test sau thấy đề đã có
     * testdata trong khi nó không nên có.
     */
    private static void jobNen(JdbcClient jdbc) {
        jdbc.sql("DELETE FROM jobs").update();
        jdbc.sql("UPDATE problems SET current_testdata_version = 1").update();
        jdbc.sql("DELETE FROM sample_testcase_contents WHERE testcase_id IN "
                + "(SELECT id FROM testcases WHERE testdata_version > 1)").update();
        jdbc.sql("DELETE FROM testcases WHERE testdata_version > 1").update();
        jdbc.sql("DELETE FROM testdata_versions WHERE version > 1").update();
    }

    private static void rateLimit(StringRedisTemplate redis) {
        var khoa = redis.keys("oj:ratelimit:*");
        if (khoa != null && !khoa.isEmpty()) {
            redis.delete(khoa);
        }
    }
}
