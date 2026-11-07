package dev.oj.platform.jobs.infrastructure;

final class JobSql {

    static final String TAO = """
            INSERT INTO jobs (type, params, created_by)
            VALUES (:type, CAST(:params AS jsonb), :createdBy)
            RETURNING id
            """;

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

    static final String DANG_SONG = """
            SELECT id, type, status, params, cursor_state, total_items, done_items,
                   lease_owner, lease_until, created_by, created_at, started_at,
                   finished_at, error_message
              FROM jobs
             WHERE type = :type
               AND COALESCE(params->>'problemId', params->>'contestId', '') = :khoa
               AND status IN ('PENDING','RUNNING','PAUSED')
            """;

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

