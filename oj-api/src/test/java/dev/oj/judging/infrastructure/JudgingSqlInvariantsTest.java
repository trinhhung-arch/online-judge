package dev.oj.judging.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Đọc thẳng các hằng SQL trong tầng {@code infrastructure} và kiểm những tính chất mà
 * {@code docs/build-order.md} Bước M1-7 cảnh báo là dễ mất nhất:
 *
 * <blockquote>
 * "Viết lại 'cho gọn' là cách nhanh nhất mất {@code SKIP LOCKED}, mất {@code RETURNING},
 * hoặc thêm một {@code COUNT(*)}."
 * </blockquote>
 *
 * <h2>Vì sao test này tồn tại thay cho {@code SchemaInvariantsIT}</h2>
 * Test đúng cho tầng này là Testcontainers với Postgres thật — H2 không có partial index,
 * {@code SKIP LOCKED}, hay {@code ON CONFLICT ... WHERE}. Nhưng Testcontainers cần một
 * dependency mới (phải hỏi người, {@code CLAUDE.md} mục 5.2) và cần Docker.
 *
 * <p>Test này <b>không thay thế</b> nó: nó không chạy một câu SQL nào, nên nó không biết câu
 * lệnh có đúng cú pháp hay không. Cái nó giữ là thứ khác và vẫn đáng giữ — một lần refactor
 * "cho gọn" làm mất {@code SKIP LOCKED} sẽ đỏ ở đây, ngay lập tức, không cần Docker. Xoá nó
 * khi {@code SchemaInvariantsIT} đã chạy trong CI.
 */
class JudgingSqlInvariantsTest {

    /** Mọi hằng SQL của bốn repository, khoá là {@code Class.FIELD}. */
    private static final Map<String, String> SQL = doc();

    @Test
    @DisplayName("★ claim giữ FOR UPDATE SKIP LOCKED — thứ làm hai worker không nhận trùng bài")
    void claim_giu_skip_locked_va_tang_attempt() {
        String claim = sql("JdbcJudgeQueueRepository.CLAIM");

        assertThat(claim).contains("FOR UPDATE SKIP LOCKED");
        assertThat(claim).contains("attempt = q.attempt + 1");
        assertThat(claim).contains("RETURNING");
        // Thứ tự lấy việc: ưu tiên trước, rồi tới lượt vào hàng. Đổi nó là đổi tính công bằng.
        assertThat(claim).contains("ORDER BY priority, enqueued_at, submission_id");
    }

    @Test
    @DisplayName("★ khoá lạc quan là DELETE có điều kiện attempt, và có RETURNING")
    void khoa_lac_quan_van_con_nguyen() {
        String release = sql("JdbcJudgeQueueRepository.RELEASE_WITH_OPTIMISTIC_LOCK");

        assertThat(release).startsWith("DELETE FROM judge_queue");
        assertThat(release).contains("q.attempt = :attempt");
        assertThat(release).contains("RETURNING");
        // Hai cột này là lý do câu lệnh dùng USING submissions — thiếu chúng thì phải thêm
        // một SELECT vào đúng transaction ngắn nhất của hệ thống.
        assertThat(release).contains("s.language_id").contains("s.testdata_version");
    }

    @Test
    @DisplayName("★ reaper KHÔNG đụng tới attempt — lần claim kế tiếp mới tăng")
    void reaper_khong_tang_attempt() {
        assertThat(sql("JdbcJudgeQueueRepository.REAP_EXPIRED"))
                .doesNotContain("attempt")
                .contains("lease_until < now()")
                .contains("RETURNING");
    }

    @Test
    @DisplayName("★ markDone mang điều kiện attempt + status='JUDGING' — lớp bảo vệ thứ ba của bất biến #7")
    void mark_done_van_con_dieu_kien_trang_thai() {
        assertThat(sql("JdbcSubmissionRepository.MARK_DONE"))
                .contains("attempt = :attempt")
                .contains("status = 'JUDGING'");
    }

    @Test
    @DisplayName("FR-SUB-12: retryIe có trần lượt và điều kiện attempt")
    void retry_ie_co_tran_va_khoa_theo_attempt() {
        assertThat(sql("JdbcJudgeQueueRepository.RETRY_IE"))
                .contains("ie_retry_count < :maxIeRetries")
                .contains("attempt = :attempt")
                .contains("ie_retry_count = ie_retry_count + 1");
    }

    @Test
    @DisplayName("★ bất biến #8: không OFFSET, không SELECT *, và không COUNT trên submissions")
    void khong_co_cau_nao_quet_bang_nong() {
        SQL.forEach((name, sql) -> {
            assertThat(sql).as("%s dùng OFFSET — phân trang phải là cursor-based", name)
                    .doesNotContain("OFFSET");
            assertThat(sql).as("%s dùng SELECT * — thêm một cột TEXT lớn là mọi truy vấn "
                    + "danh sách chậm đi mà không ai hay", name).doesNotContain("SELECT *");
            if (sql.contains("FROM submissions")) {
                assertThat(sql).as("%s đếm trên submissions — bảng đó sẽ có hàng triệu dòng. "
                        + "Đếm trên judge_queue (truy vấn 12)", name).doesNotContain("count(");
            }
        });
    }

    @Test
    @DisplayName("mọi câu ghi lặp lại được đều là ON CONFLICT DO NOTHING, không phải kiểm-rồi-ghi")
    void cac_cau_ghi_deu_idempotent() {
        assertThat(sql("JdbcSourceBlobRepository.SAVE_IF_ABSENT"))
                .contains("ON CONFLICT (sha256) DO NOTHING");
        assertThat(sql("JdbcJudgeQueueRepository.ENQUEUE"))
                .contains("ON CONFLICT (submission_id) DO NOTHING");
        assertThat(sql("JdbcJudgeRunRepository.INSERT_IF_ABSENT"))
                .contains("ON CONFLICT (submission_id, attempt) DO NOTHING");
    }

    @Test
    @DisplayName("bất biến #5: không hằng SQL nào được dựng bằng nối chuỗi")
    void moi_cau_sql_deu_la_hang_so_va_dung_named_parameter() {
        assertThat(SQL).isNotEmpty();
        SQL.forEach((name, sql) -> {
            // Dấu ? là tham số theo VỊ TRÍ — đảo hai cái là sai âm thầm. Chỉ dùng :tên.
            assertThat(sql).as("%s dùng tham số '?' theo vị trí", name).doesNotContain("?");
            assertThat(sql).as("%s có vẻ được ghép chuỗi", name).doesNotContain("' +", "+ '");
        });
    }

    /** Truy vấn 6: chống IDOR bằng điều kiện chủ sở hữu NẰM TRONG câu query. */
    @Test
    void hai_cau_doc_bai_nop_deu_mang_dieu_kien_chu_so_huu() {
        assertThat(sql("JdbcSubmissionRepository.FIND_FOR_REQUESTER"))
                .contains("user_id = :requesterId")
                .contains(":requesterRole = 'ADMIN'");
        assertThat(sql("JdbcSubmissionRepository.LIST_FOR_USER"))
                .contains("s.user_id = :userId")
                .contains("s.hidden_at IS NULL")
                .contains("ORDER BY s.id DESC")
                .contains("LIMIT :pageSize");
    }

    private static String sql(String key) {
        String value = SQL.get(key);
        assertThat(value).as("không tìm thấy hằng SQL %s — đổi tên field thì sửa test này", key)
                .isNotNull();
        return value;
    }

    /** Gom mọi field {@code private static final String} chứa SQL của bốn repository. */
    private static Map<String, String> doc() {
        Map<String, String> all = new LinkedHashMap<>();
        for (Class<?> type : new Class<?>[]{
                JdbcJudgeQueueRepository.class, JdbcSubmissionRepository.class,
                JdbcJudgeRunRepository.class, JdbcSourceBlobRepository.class}) {
            for (Field f : type.getDeclaredFields()) {
                if (f.getType() == String.class && Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    try {
                        // Gộp khoảng trắng: text block xuống dòng và thụt đầu dòng không phải
                        // thứ ta muốn kiểm, còn "status = 'JUDGING'" thì có.
                        String value = ((String) f.get(null)).replaceAll("\\s+", " ").trim();
                        all.put(type.getSimpleName() + "." + f.getName(), value);
                    } catch (IllegalAccessException e) {
                        throw new IllegalStateException(e);
                    }
                }
            }
        }
        return all;
    }
}
