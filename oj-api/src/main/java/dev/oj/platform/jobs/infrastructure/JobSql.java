package dev.oj.platform.jobs.infrastructure;

/**
 * Mọi câu SQL của {@link JdbcJobRepository}, tách ra vì file đó vượt trần 300 dòng
 * ({@code CLAUDE.md} mục 7).
 *
 * <p>Vẫn nằm trong {@code infrastructure} và vẫn là text block gán vào hằng — đúng hai điều
 * {@code scripts/kiem-noi-chuoi-sql.sh} dựa vào để phân biệt SQL với câu log (bất biến #5).
 * Package-private: không ai ngoài repository được cầm câu SQL thô.
 */
final class JobSql {

    static final String TAO = """
            INSERT INTO jobs (type, params, created_by)
            VALUES (:type, CAST(:params AS jsonb), :createdBy)
            RETURNING id
            """;

    /**
     * Danh sách cột được viết lại trọn vẹn ở mỗi câu, <b>cố ý</b>.
     *
     * <p>Ghép một hằng {@code CHON} với phần {@code WHERE} thì gọn hơn bốn dòng, nhưng nó là
     * nối chuỗi trong tầng {@code infrastructure} — và bước {@code grep} của CI không phân
     * biệt được một phép ghép vô hại với một mệnh đề {@code WHERE} dựng từ dữ liệu người dùng
     * (bất biến #5, SEC2). Bộ lọc ấy thô là <b>đúng</b>: từ Java 9 phép cộng chuỗi biên dịch
     * thành {@code invokedynamic} và ArchUnit không còn thấy nó nữa.
     *
     * <p>Cái giá là sự lặp lại dưới đây. {@code JdbcProblemRepository} trả đúng cái giá ấy
     * cho cùng lý do, và ghi lại trong javadoc của nó.
     */
    static final String TIM_THEO_ID = """
            SELECT id, type, status, params, cursor_state, total_items, done_items,
                   lease_owner, lease_until, created_by, created_at, started_at,
                   finished_at, error_message
              FROM jobs
             WHERE id = :id
            """;

    static final String TIM_CHO_NGUOI_GOI = """
            SELECT id, type, status, params, cursor_state, total_items, done_items,
                   lease_owner, lease_until, created_by, created_at, started_at,
                   finished_at, error_message
              FROM jobs
             WHERE id = :id
               AND (:laAdmin OR created_by = :requesterId)
            """;

    /**
     * Job đang sống của một (loại, thực thể) — đúng dòng đã làm {@link #TAO} đụng
     * {@code ux_jobs_one_active_per_entity}.
     *
     * <p>Biểu thức {@code COALESCE(...)} và danh sách {@code status} chép <b>nguyên văn</b> từ
     * V9. Lệch một chữ là Postgres không còn chứng minh được câu này nằm trong partial index,
     * và nó quay về quét cả bảng {@code jobs} — vẫn đúng, chỉ chậm dần theo năm tháng.
     */
    static final String DANG_SONG = """
            SELECT id, type, status, params, cursor_state, total_items, done_items,
                   lease_owner, lease_until, created_by, created_at, started_at,
                   finished_at, error_message
              FROM jobs
             WHERE type = :type
               AND COALESCE(params->>'problemId', params->>'contestId', '') = :khoa
               AND status IN ('PENDING','RUNNING','PAUSED')
            """;

    /**
     * ★ {@code CAST(:x AS bigint)} quanh cả hai tham số có thể {@code NULL}.
     *
     * <p>Bản đầu viết {@code (:createdBy IS NULL OR ...)} không kèm CAST, và Postgres không
     * suy được kiểu của một tham số chỉ xuất hiện trong {@code IS NULL} — nó từ chối cả câu
     * lệnh. {@code createdBy} là {@code null} <b>đúng khi người gọi là ADMIN</b>
     * ({@code GetJobUseCase}: admin xem job của mọi người), nên endpoint này trả 500 cho mọi
     * quản trị viên và chạy bình thường cho mọi người khác — cách hỏng khó thấy nhất, vì người
     * thử nó nhiều nhất lại là người duy nhất gặp lỗi.
     *
     * <p>Cùng cái bẫy đã ghi ở {@code JdbcContestRepository} và {@code JdbcProblemRepository}.
     * {@code JdbcSubmissionRepository} dùng cú pháp {@code :x::BIGINT} cho cùng một việc.
     */
    static final String GAN_DAY = """
            SELECT id, type, status, params, cursor_state, total_items, done_items,
                   lease_owner, lease_until, created_by, created_at, started_at,
                   finished_at, error_message
              FROM jobs
             WHERE (CAST(:createdBy AS bigint) IS NULL
                    OR created_by = CAST(:createdBy AS bigint))
               AND (CAST(:sauId AS bigint) IS NULL OR id < CAST(:sauId AS bigint))
             ORDER BY id DESC
             LIMIT :gioiHan
            """;

    /**
     * {@code FOR UPDATE SKIP LOCKED} đặt SAU {@code LIMIT} — đó là thứ tự Postgres đòi, và
     * viết ngược lại là một lỗi cú pháp chứ không phải một câu chạy chậm.
     */
    static final String CLAIM = """
            UPDATE jobs
               SET status = 'RUNNING',
                   lease_owner = :owner,
                   lease_until = :until,
                   heartbeat_at = now(),
                   started_at = COALESCE(started_at, now())
             WHERE id = (SELECT id FROM jobs
                          WHERE status IN ('PENDING', 'PAUSED')
                          ORDER BY id
                          LIMIT 1
                          FOR UPDATE SKIP LOCKED)
            RETURNING id, type, status, params, cursor_state, total_items, done_items,
                      lease_owner, lease_until, created_by, created_at, started_at,
                      finished_at, error_message
            """;

    static final String NHIP_TIM = """
            UPDATE jobs
               SET done_items = :daXong,
                   total_items = COALESCE(:tong, total_items),
                   lease_until = :leaseMoi,
                   heartbeat_at = now()
             WHERE id = :id AND lease_owner = :owner AND status = 'RUNNING'
            """;

    static final String LUU_VI_TRI = """
            UPDATE jobs SET cursor_state = CAST(:viTri AS jsonb) WHERE id = :id
            """;

    static final String THU_HOI_TREO = """
            UPDATE jobs
               SET status = 'PAUSED', lease_owner = NULL, lease_until = NULL
             WHERE status = 'RUNNING' AND lease_until < :bayGio
            """;

    static final String KET_THUC = """
            UPDATE jobs
               SET status = :status, error_message = :loi, finished_at = :luc,
                   lease_owner = NULL, lease_until = NULL
             WHERE id = :id
            """;

    /** Thả lease, giữ nguyên cursor_state, KHÔNG đặt finished_at. Xem JobRepository.tamNghi. */
    static final String TAM_NGHI = """
            UPDATE jobs
               SET status = 'PAUSED', error_message = :lyDo,
                   lease_owner = NULL, lease_until = NULL
             WHERE id = :id
            """;

    static final String HUY = """
            UPDATE jobs
               SET status = 'CANCELLED', finished_at = :luc,
                   lease_owner = NULL, lease_until = NULL
             WHERE id = :id AND status IN ('PENDING', 'RUNNING', 'PAUSED')
            """;

    static final String GHI_SU_KIEN = """
            INSERT INTO job_events (job_id, level, message)
            VALUES (:jobId, :muc, :thongDiep)
            """;

    static final String SU_KIEN_GAN_DAY = """
            SELECT at, level, message
              FROM job_events
             WHERE job_id = :jobId
             ORDER BY id DESC
             LIMIT :gioiHan
            """;

    private JobSql() {
    }
}
