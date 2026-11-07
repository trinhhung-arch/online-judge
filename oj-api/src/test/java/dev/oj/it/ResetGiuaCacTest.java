package dev.oj.it;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

final class ResetGiuaCacTest {

    static final String KHOA_RATE_LIMIT = "oj:ratelimit:submit:";

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

    private static void duongCham(JdbcClient jdbc) {
        jdbc.sql("DELETE FROM judge_runs").update();
        jdbc.sql("DELETE FROM judge_queue").update();
        jdbc.sql("DELETE FROM submissions").update();
        jdbc.sql("DELETE FROM source_blobs").update();

        jdbc.sql("DELETE FROM host_benchmarks").update();
        jdbc.sql("UPDATE judge_hosts SET host_factor = 1.000, last_seen_at = NULL").update();
        jdbc.sql("""
                UPDATE problems SET feedback_level = 'TEST_INDEX',
                scoring_mode = 'ALL_OR_NOTHING'
                """).update();

        jdbc.sql("UPDATE problems SET status = 'PUBLISHED' WHERE id = 1").update();

        jdbc.sql("UPDATE testcases SET subtask_id = NULL WHERE subtask_id IS NOT NULL").update();
        jdbc.sql("DELETE FROM subtasks").update();

        jdbc.sql("DELETE FROM contests").update();

        jdbc.sql("DELETE FROM problems WHERE id > 1").update();
    }

    private static void danhTinh(JdbcClient jdbc) {
        jdbc.sql("DELETE FROM user_scratch_code").update();
        jdbc.sql("DELETE FROM user_two_factor").update();
        jdbc.sql("DELETE FROM email_verifications").update();
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
                    -- V13. Không có dòng này thì một test xác minh xong email của `dev` sẽ
                    -- để lại nhãn ấy cho MỌI test sau — và test kiểm "tài khoản seed chưa
                    -- xác minh" đỏ vì một việc nó không làm. Lần thứ NĂM của bài học ở
                    -- javadoc lớp này.
                    email_verified_at = NULL,
                    password_hash = :bam
                 WHERE id <= 3
                """).param("bam", BAM_MAT_KHAU_DEV).update();
    }

    private static void jobNen(JdbcClient jdbc) {
        jdbc.sql("DELETE FROM jobs").update();
        jdbc.sql("UPDATE problems SET current_testdata_version = 1").update();
        jdbc.sql("""
                DELETE FROM sample_testcase_contents WHERE testcase_id IN
                (SELECT id FROM testcases WHERE testdata_version > 1)
                """).update();
        jdbc.sql("DELETE FROM testcases WHERE testdata_version > 1").update();
        jdbc.sql("DELETE FROM testdata_versions WHERE version > 1").update();
    }

    private static void rateLimit(StringRedisTemplate redis) {
        xoaTheoMau(redis, "oj:ratelimit:*");
        xoaTheoMau(redis, "oj:standings:*");
    }

    private static void xoaTheoMau(StringRedisTemplate redis, String mau) {
        var khoa = redis.keys(mau);
        if (khoa != null && !khoa.isEmpty()) {
            redis.delete(khoa);
        }
    }
}

