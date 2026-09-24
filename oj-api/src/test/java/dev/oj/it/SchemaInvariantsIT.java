package dev.oj.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Chín ca của {@code docs/sql/smoke_test.sql} chạy được ở M1, thành test tự động
 * ({@code build-order.md} Bước M1-7). Ba ca còn lại cần V5/V7/V8 và sẽ thêm cùng chúng.
 *
 * <p>Đây là loại lỗi mà unit test với repository giả <b>không bao giờ</b> bắt được: chúng
 * không nằm trong code Java, chúng nằm trong ràng buộc của schema.
 */
class SchemaInvariantsIT extends PostgresIT {

    /**
     * ★ Ca quan trọng nhất — bất biến #1 ở tầng schema.
     *
     * <p>Muốn lưu nội dung cho một testcase ẩn, khoá ngoại phải khớp {@code (id, FALSE)},
     * nhưng cột {@code is_sample} bên bảng nội dung bị {@code CHECK} ép luôn bằng {@code TRUE}.
     * <b>Không có đường nào đi qua</b> — Postgres không có chỗ nào để lưu testdata ẩn, nên
     * việc lưu nhầm không phải là "đừng làm" mà là "không làm được".
     */
    @Test
    @DisplayName("★ không thể lưu nội dung cho một testcase ẨN, dù có cố")
    void khong_the_luu_noi_dung_testcase_an() {
        Long hiddenTestcaseId = jdbc.sql("""
                SELECT id FROM testcases
                 WHERE problem_id = :p AND testdata_version = 1 AND is_sample = FALSE
                 LIMIT 1
                """).param("p", PROBLEM_ID).query(Long.class).single();

        assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() ->
                jdbc.sql("""
                        INSERT INTO sample_testcase_contents (testcase_id, input_text, output_text)
                        VALUES (:id, 'bí mật', 'bí mật')
                        """).param("id", hiddenTestcaseId).update());
    }

    @Test
    void testcase_sample_thi_luu_duoc_binh_thuong() {
        Integer count = jdbc.sql("""
                SELECT count(*)::int FROM sample_testcase_contents c
                  JOIN testcases t ON t.id = c.testcase_id
                 WHERE t.problem_id = :p AND t.is_sample
                """).param("p", PROBLEM_ID).query(Integer.class).single();

        assertThat(count).isEqualTo(1);   // dev-seed có đúng một test sample
    }

    /** {@code ck_submissions_done}: DONE mà không có verdict là trạng thái không tồn tại được. */
    @Test
    @DisplayName("★ không thể đánh dấu DONE mà không có verdict")
    void khong_the_DONE_ma_thieu_verdict() {
        insertBlob("aa");
        assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() ->
                jdbc.sql("""
                        INSERT INTO submissions (user_id, problem_id, language_id, source_sha256,
                                                 source_bytes, testdata_version, status)
                        VALUES (:u, :p, 1, :sha, 12, 1, 'DONE')
                        """)
                        .param("u", USER_ID).param("p", PROBLEM_ID).param("sha", sha("aa"))
                        .update());
    }

    /** FR-SUB-01 · giới hạn 64KB là một {@code CHECK}, không phải một câu {@code if}. */
    @Test
    void khong_the_luu_source_qua_64KB() {
        assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() ->
                jdbc.sql("""
                        INSERT INTO source_blobs (sha256, content, byte_size)
                        VALUES (:sha, 'x', 65537)
                        """).param("sha", sha("qua-dai")).update());
    }

    /** {@code ck_judge_queue_claim}: "đã giao mà không có hạn" là bài reaper không nhặt được. */
    @Test
    void khong_the_giao_job_ma_khong_dat_han_lease() {
        long id = insertQueuedSubmission("bb");

        assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() ->
                jdbc.sql("UPDATE judge_queue SET claimed_at = now() WHERE submission_id = :id")
                        .param("id", id).update());
    }

    /** Chỉ tồn tại đúng một "máy chấm chuẩn" — mọi con số thời gian quy chiếu về nó. */
    @Test
    void chi_co_mot_may_cham_chuan() {
        assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() ->
                jdbc.sql("""
                        INSERT INTO judge_hosts (name, arch, judge_slots, is_reference)
                        VALUES ('may-thu-hai', 'amd64', 4, TRUE)
                        """).update());
    }

    /** {@code ck_problems_epsilon}: epsilon có khi và chỉ khi checker là {@code float}. */
    @Test
    void epsilon_chi_ton_tai_cung_checker_float() {
        assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() ->
                jdbc.sql("UPDATE problems SET checker_epsilon = 0.001 WHERE id = :p")
                        .param("p", PROBLEM_ID).update());
    }

    /** {@code judge_runs} không nhận {@code attempt = 0}: số đó tăng lúc claim. */
    @Test
    void judge_runs_khong_nhan_attempt_0() {
        long id = insertQueuedSubmission("cc");

        assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() ->
                jdbc.sql("""
                        INSERT INTO judge_runs (submission_id, attempt, host_factor, language_id,
                                                testdata_version, verdict)
                        VALUES (:id, 0, 1.000, 1, 1, 'AC')
                        """).param("id", id).update());
    }

    /** Khoá chính {@code (submission_id, attempt)} — lớp chống trùng thứ hai. */
    @Test
    void mot_attempt_chi_co_dung_mot_ban_ghi_cham() {
        long id = insertQueuedSubmission("dd");
        insertJudgeRun(id);

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insertJudgeRun(id));
    }

    /**
     * ★ {@code postgres-design.md} mục 15 cấm {@code ON DELETE CASCADE} trỏ vào {@code users}
     * hay {@code submissions} — "không ai bị xoá, cascade chỉ tạo ảo giác là xoá được".
     *
     * <h2>Vì sao ca này là một DANH SÁCH TRẮNG chứ không phải một lệnh cấm</h2>
     * Ba bảng dưới đây vi phạm câu chữ của luật và <b>được miễn có chủ ý</b>: chúng chỉ chứa
     * <i>bí mật dẫn xuất của đúng một user</i> — bí mật TOTP, mã dự phòng, mã xác minh email —
     * không chứa một dòng lịch sử nào. Với chúng, cascade không phải ảo giác xoá được: nếu một
     * dòng {@code users} thật sự biến mất thì những bí mật ấy <b>phải</b> biến mất theo.
     *
     * <p>Thứ luật thật sự bảo vệ là {@code submissions} và mọi bảng mang lịch sử của người
     * dùng: FR-AUTH-07 <b>ẩn danh hoá</b> chứ không xoá dòng, nên một cascade ở đó sẽ hứa một
     * thao tác mà hệ thống cố ý không có.
     *
     * <h2>Vì sao viết thành test thay vì một câu trong tài liệu</h2>
     * Tính tới 2026-09-20, ba khoá ngoại này đã nằm trong schema suốt từ V11 mà không ai biết,
     * và mục 15 phải ghi chú "không có test nào bắt được chuyện này": ArchUnit không đọc SQL,
     * {@code smoke_test.sql} không kiểm {@code confdeltype}. Một danh sách trắng thì cái thứ
     * tư lọt vào là <b>đỏ ngay</b>, và người thêm nó buộc phải nói ra bảng ấy chứa gì.
     */
    @Test
    @DisplayName("★ chỉ đúng ba bảng bí mật dẫn xuất được CASCADE tới users — cái thứ tư là đỏ")
    void chi_ba_bang_duoc_cascade_toi_users() {
        var cascade = jdbc.sql("""
                SELECT con.conrelid::regclass::text
                FROM pg_constraint con
                JOIN pg_class ref ON ref.oid = con.confrelid
                WHERE con.contype = 'f'
                  AND ref.relname = :bang
                  AND con.confdeltype = 'c'
                ORDER BY 1
                """).param("bang", "users").query(String.class).list();

        assertThat(cascade)
                .as("""
                        Thêm một ON DELETE CASCADE → users là một quyết định, không phải một \
                        chi tiết. Nếu bảng mới chỉ chứa bí mật dẫn xuất của một user thì thêm \
                        tên nó vào đây VÀ vào postgres-design.md mục 15. Nếu nó chứa bất cứ \
                        thứ gì là lịch sử của người dùng thì bỏ CASCADE đi — FR-AUTH-07 ẩn \
                        danh hoá chứ không xoá.""")
                .containsExactlyInAnyOrder(
                        "user_two_factor", "user_scratch_code", "email_verifications");
    }

    /** Không bảng nào được CASCADE tới {@code submissions} — ở đó luật không có ngoại lệ. */
    @Test
    @DisplayName("★ KHÔNG bảng nào được CASCADE tới submissions")
    void khong_bang_nao_cascade_toi_submissions() {
        var cascade = jdbc.sql("""
                SELECT con.conrelid::regclass::text
                FROM pg_constraint con
                JOIN pg_class ref ON ref.oid = con.confrelid
                WHERE con.contype = 'f'
                  AND ref.relname = :bang
                  AND con.confdeltype = 'c'
                """).param("bang", "submissions").query(String.class).list();

        assertThat(cascade)
                .as("submissions là lịch sử — xoá dây chuyền ở đây là mất bài nộp thật")
                .isEmpty();
    }

    // ---- trợ giúp ----

    private void insertBlob(String seed) {
        jdbc.sql("""
                INSERT INTO source_blobs (sha256, content, byte_size)
                VALUES (:sha, :content, :size) ON CONFLICT DO NOTHING
                """)
                .param("sha", sha(seed)).param("content", "int main(){}").param("size", 12)
                .update();
    }

    protected long insertQueuedSubmission(String seed) {
        insertBlob(seed);
        Long id = jdbc.sql("""
                INSERT INTO submissions (user_id, problem_id, language_id, source_sha256,
                                         source_bytes, testdata_version)
                VALUES (:u, :p, 1, :sha, 12, 1)
                RETURNING id
                """)
                .param("u", USER_ID).param("p", PROBLEM_ID).param("sha", sha(seed))
                .query(Long.class).single();
        jdbc.sql("INSERT INTO judge_queue (submission_id, priority, attempt) VALUES (:id, 0, 0)")
                .param("id", id).update();
        return id;
    }

    private void insertJudgeRun(long submissionId) {
        jdbc.sql("""
                INSERT INTO judge_runs (submission_id, attempt, host_factor, language_id,
                                        testdata_version, verdict)
                VALUES (:id, 1, 1.000, 1, 1, 'AC')
                """).param("id", submissionId).update();
    }

    protected static String sha(String seed) {
        return dev.oj.contract.Sha256.hexOf(seed);
    }
}
