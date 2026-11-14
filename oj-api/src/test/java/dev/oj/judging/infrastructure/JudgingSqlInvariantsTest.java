package dev.oj.judging.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JudgingSqlInvariantsTest {

    private static final Map<String, String> SQL = doc();

    @Test
    @DisplayName("★ claim giữ FOR UPDATE SKIP LOCKED — thứ làm hai worker không nhận trùng bài")
    void claim_giu_skip_locked_va_tang_attempt() {
        String claim = sql("JdbcJudgeQueueRepository.CLAIM");

        assertThat(claim).contains("FOR UPDATE SKIP LOCKED");
        assertThat(claim).contains("attempt = q.attempt + 1");
        assertThat(claim).contains("RETURNING");
        assertThat(claim).contains("ORDER BY priority, enqueued_at, submission_id");
    }

    @Test
    @DisplayName("★ khoá lạc quan là DELETE có điều kiện attempt, và có RETURNING")
    void khoa_lac_quan_van_con_nguyen() {
        String release = sql("JdbcJudgeQueueRepository.RELEASE_WITH_OPTIMISTIC_LOCK");

        assertThat(release).startsWith("DELETE FROM judge_queue");
        assertThat(release).contains("q.attempt = :attempt");
        assertThat(release)
                .as("KQ-04: reaper không tăng attempt, nên chỉ lượt còn giữ lease mới được ghi")
                .contains("q.claimed_at IS NOT NULL");
        assertThat(release).contains("RETURNING");
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
                .contains("claimed_at IS NOT NULL")
                .contains("ie_retry_count = ie_retry_count + 1");
    }

    @Test
    @DisplayName("★ ADR 026: gia hạn lease khoá theo attempt + lượt còn giữ lease, và không đụng attempt")
    void gia_han_lease_chi_cho_luot_con_giu_lease() {
        String renew = sql("JdbcJudgeQueueRepository.RENEW_LEASE");

        assertThat(renew).startsWith("UPDATE judge_queue SET lease_until = now() +");
        assertThat(renew)
                .as("thiếu một trong hai là worker đã mất lease giữ sống được lượt chấm của máy khác")
                .contains("attempt = :attempt")
                .contains("claimed_at IS NOT NULL");
        assertThat(renew).doesNotContain("attempt +", "claimed_at =");
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
            assertThat(sql).as("%s dùng tham số '?' theo vị trí", name).doesNotContain("?");
            assertThat(sql).as("%s có vẻ được ghép chuỗi", name).doesNotContain("' +", "+ '");
        });
    }

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

    private static Map<String, String> doc() {
        Map<String, String> all = new LinkedHashMap<>();
        for (Class<?> type : new Class<?>[]{
                JdbcJudgeQueueRepository.class, JdbcSubmissionRepository.class,
                JdbcJudgeRunRepository.class, JdbcSourceBlobRepository.class}) {
            for (Field f : type.getDeclaredFields()) {
                if (f.getType() == String.class && Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    try {
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
