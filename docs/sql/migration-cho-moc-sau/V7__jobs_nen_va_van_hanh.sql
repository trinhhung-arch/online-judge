-- =============================================================================
-- V7 — Job nền và vận hành
-- Mốc: M6 (tuần 10-12). FR-ADM-01..06, Quy tắc 5 của frplan.
--
-- Quy tắc 5: mọi thao tác có thể vượt 5 giây là job nền có tiến độ và
-- CHẠY TIẾP ĐƯỢC SAU KHI RESTART. "Chạy tiếp được" nghĩa là tiến độ phải nằm
-- trong DB chứ không trong bộ nhớ tiến trình -> cột `cursor_state`.
-- =============================================================================

CREATE TABLE jobs (
    id            BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    type          TEXT        NOT NULL CHECK (type IN (
                        'REJUDGE',              -- FR-ADM-01
                        'TESTDATA_IMPORT',      -- FR-PROB-03 (ZIP 200MB)
                        'LEADERBOARD_REBUILD',  -- FR-CON-08
                        'STANDINGS_DRIFT_CHECK',-- FR-CON-09
                        'HOST_BENCHMARK')),     -- hiệu chuẩn host_factor
    status        TEXT        NOT NULL DEFAULT 'PENDING' CHECK (status IN (
                        'PENDING','RUNNING','PAUSED','DONE','FAILED','CANCELLED')),
    params        JSONB       NOT NULL DEFAULT '{}'::jsonb,
    -- Vị trí đang chạy tới, ví dụ {"last_submission_id": 918233}. Đây là thứ
    -- làm cho job sống sót qua restart.
    cursor_state  JSONB       NOT NULL DEFAULT '{}'::jsonb,
    total_items   INTEGER,
    done_items    INTEGER     NOT NULL DEFAULT 0 CHECK (done_items >= 0),

    -- Lease để hai instance API không cùng chạy một job
    lease_owner   TEXT,
    lease_until   TIMESTAMPTZ,
    heartbeat_at  TIMESTAMPTZ,

    created_by    BIGINT      REFERENCES users(id),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at    TIMESTAMPTZ,
    finished_at   TIMESTAMPTZ,
    error_message TEXT,

    CONSTRAINT ck_jobs_finished CHECK (
        status NOT IN ('DONE','FAILED','CANCELLED') OR finished_at IS NOT NULL)
);

-- Mỗi loại job chỉ được có tối đa MỘT job đang sống. Đây là hàng rào chống
-- thảm hoạ "ba job rejudge hàng loạt chạy song song" — thứ mà một cú double
-- click trên trang admin là đủ để tạo ra.
CREATE UNIQUE INDEX ux_jobs_one_active_per_type ON jobs (type)
    WHERE status IN ('PENDING','RUNNING','PAUSED');

CREATE INDEX ix_jobs_recent ON jobs (id DESC);

-- Nhật ký tiến độ để trang theo dõi không phải poll cột done_items mù
CREATE TABLE job_events (
    id         BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_id     BIGINT      NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    level      TEXT        NOT NULL DEFAULT 'INFO' CHECK (level IN ('INFO','WARN','ERROR')),
    message    TEXT        NOT NULL,
    detail     JSONB       NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX ix_job_events_job ON job_events (job_id, id DESC);

-- -----------------------------------------------------------------------------
-- Đo tải hàng đợi cho FR-ADM-04 (dashboard) và FR-ADM-05 (trang trạng thái
-- công khai: "hiện có N bài đang chờ, ước tính Xs").
-- Ghi mỗi 10 giây bởi job nền — KHÔNG tính COUNT(*) trên `submissions`
-- (bất biến #8 / oj-api mục 3): đếm trên `judge_queue` vài trăm dòng là đủ.
-- -----------------------------------------------------------------------------
CREATE TABLE queue_metrics (
    sampled_at        TIMESTAMPTZ NOT NULL PRIMARY KEY,
    queued_count      INTEGER     NOT NULL,
    judging_count     INTEGER     NOT NULL,
    oldest_wait_ms    INTEGER     NOT NULL,
    live_workers      SMALLINT    NOT NULL,
    est_wait_ms       INTEGER     NOT NULL
);
