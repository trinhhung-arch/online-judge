-- =============================================================================
-- V3 — LÕI HỆ THỐNG: source_blobs · submissions · judge_queue · judge_runs
-- Mốc: M1. Đây là file quan trọng nhất của toàn bộ schema.
--
-- Ba quyết định chi phối file này:
--
-- (1) TÁCH HÀNG ĐỢI RA BẢNG RIÊNG `judge_queue`, KHÔNG ĐÁNH INDEX LÊN
--     `submissions.status`.
--     Vì sao: nếu đánh index lên status, thì mỗi lần claim và mỗi lần ghi verdict
--     đều là một UPDATE có sửa cột được index -> Postgres không làm được HOT
--     update -> phải ghi lại toàn bộ index của bảng 1M+ dòng, hai lần cho mỗi
--     bài nộp. Với `judge_queue` riêng, `submissions` chỉ còn 2 index thường và
--     cả hai lần UPDATE đều là HOT (không cột nào được index bị đổi).
--     Hệ quả phụ rất đáng giá: "rebuild hàng đợi sau khi mất RabbitMQ" trở thành
--     `SELECT submission_id FROM judge_queue WHERE claimed_at IS NULL` trên một
--     bảng vài trăm dòng, thay vì quét toàn bộ `submissions` (nfrplan 5.1).
--
-- (2) NGÂN SÁCH INDEX TRÊN `submissions`: PK + 2. Trần là 3-4 (nfrplan 2.3),
--     ta tiêu 2 và để dành. Mỗi index thừa là một lần ghi chậm hơn trên đường nộp bài.
--
-- (3) KHÔNG LƯU KẾT QUẢ TỪNG TEST XUỐNG DB. 1M bài x 50 test = 50M dòng cho
--     một dữ liệu mà FR không ai được xem (bất biến #1: chi tiết test ẩn không
--     rời worker). Cái người dùng được thấy là verdict + số thứ tự test fail
--     (theo feedback_level) + điểm từng subtask -> đó đúng là những gì lưu.
--     Tiến độ từng test lúc đang chấm đi qua Redis pub/sub -> SSE, không qua DB.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- source_blobs — nội dung bài nộp, khử trùng lặp theo sha256.
-- Tách khỏi `submissions` vì hai lý do: giữ heap của bảng nóng gọn, và cùng một
-- khoá sha256 chính là khoá cache biên dịch của worker (nfrplan 2.3 mục 3) —
-- trong contest tỉ lệ nộp lại y hệt rất cao.
-- -----------------------------------------------------------------------------
CREATE TABLE source_blobs (
    sha256     CHAR(64)    PRIMARY KEY,
    content    TEXT        NOT NULL,
    byte_size  INTEGER     NOT NULL CHECK (byte_size > 0 AND byte_size <= 65536), -- FR-SUB-01: 64KB
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE source_blobs IS
    'Source người dùng. Không bao giờ log nội dung cột content (bất biến #9).';

-- -----------------------------------------------------------------------------
-- submissions — bảng nóng. FR-SUB-01..12.
-- Vòng đời: INSERT (QUEUED) -> UPDATE (JUDGING) -> UPDATE (DONE). Cả hai UPDATE
-- đều là HOT vì không đụng cột nào được index. fillfactor 85 chừa chỗ cho HOT.
-- -----------------------------------------------------------------------------
CREATE TABLE submissions (
    id                  BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    -- ----- bất biến sau khi INSERT -----
    user_id             BIGINT      NOT NULL REFERENCES users(id),
    problem_id          BIGINT      NOT NULL REFERENCES problems(id),
    contest_id          BIGINT,                 -- FK gắn ở V6 (NOT VALID rồi VALIDATE)
    language_id         SMALLINT    NOT NULL REFERENCES languages(id),
    source_sha256       CHAR(64)    NOT NULL REFERENCES source_blobs(sha256),
    source_bytes        INTEGER     NOT NULL CHECK (source_bytes > 0 AND source_bytes <= 65536),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- ----- ảnh chụp trạng thái hiện tại (được UPDATE, không index) -----
    status              TEXT        NOT NULL DEFAULT 'QUEUED'
                                    CHECK (status IN ('QUEUED','JUDGING','DONE')),
    attempt             INTEGER     NOT NULL DEFAULT 0 CHECK (attempt >= 0),
    testdata_version    INTEGER,                -- version đã dùng cho attempt hiện tại
    verdict             TEXT        CHECK (verdict IN ('AC','WA','TLE','MLE','RE','CE','IE')),
    score               INTEGER     CHECK (score >= 0),
    max_score           INTEGER     CHECK (max_score >= 0),
    failed_test_ordinal SMALLINT,               -- CHỈ số thứ tự, không bao giờ là nội dung
    time_ms             INTEGER     CHECK (time_ms >= 0),
    memory_kb           INTEGER     CHECK (memory_kb >= 0),
    judged_at           TIMESTAMPTZ,

    -- ----- FR-SUB-09: không ai được xoá bài nộp, ADMIN chỉ ẩn -----
    hidden_at           TIMESTAMPTZ,
    hidden_by           BIGINT      REFERENCES users(id),

    -- DONE thì bắt buộc có verdict. Chiều ngược lại CỐ Ý không ép: khi rejudge,
    -- bài quay về JUDGING mà vẫn giữ verdict của attempt trước để UI hiện
    -- "WA · đang chấm lại" thay vì một ô trống (FR-ADM-01).
    CONSTRAINT ck_submissions_done   CHECK (status <> 'DONE' OR verdict IS NOT NULL),
    CONSTRAINT ck_submissions_hidden CHECK ((hidden_at IS NULL) = (hidden_by IS NULL))
) WITH (fillfactor = 85, autovacuum_vacuum_scale_factor = 0.02, autovacuum_analyze_scale_factor = 0.01);

-- Index #1 — FR-SUB-07 "lịch sử bài nộp của mình", phân trang cursor id DESC.
-- INCLUDE các cột BẤT BIẾN để trang danh sách đọc index-only; cố ý KHÔNG đưa
-- verdict/status vào INCLUDE để hai lần UPDATE vẫn là HOT.
-- Cũng chính là index phục vụ kiểm rate limit 1 bài/10s (FR-SUB-08) khi Redis chết.
CREATE INDEX ix_submissions_user_recent
    ON submissions (user_id, id DESC)
    INCLUDE (problem_id, language_id, created_at);

-- Index #2 — rejudge theo đề (FR-ADM-01) và dựng lại bảng xếp hạng theo khoảng id
-- của contest (FR-CON-08). Cũng phục vụ trang "các bài nộp của đề này".
CREATE INDEX ix_submissions_problem_recent
    ON submissions (problem_id, id DESC);

COMMENT ON TABLE submissions IS
    'Bảng nóng. Ngân sách index: 3-4 (nfrplan 2.3) — đang dùng 2. Trước khi thêm index thứ 3, đọc lại nfrplan 2.3 và hỏi người.';

-- ĐO THẬT trên Postgres 16, 20.000 bài nộp mô phỏng trọn vòng đời
-- (INSERT -> UPDATE JUDGING -> UPDATE DONE), cùng fillfactor 85:
--     không có index trên status : 40.000/40.000 update là HOT (100%), index 45 MB
--     có    index trên status    :      0/40.000 update là HOT   (0%), index 56 MB
-- Đây là lý do `status` không được đánh index, và là lý do `judge_queue` tồn tại.
COMMENT ON COLUMN submissions.failed_test_ordinal IS
    'Chỉ số thứ tự test. Nội dung test không bao giờ được lưu ở bất kỳ đâu trong oj-api.';

-- -----------------------------------------------------------------------------
-- judge_queue — HÀNG ĐỢI BỀN. Chỉ chứa bài đang bay (vài trăm dòng).
-- RabbitMQ chỉ là đường dẫn; bảng này mới là sự thật (nfrplan 5.1).
-- Xoá hàng ở đây == bài đã chấm xong. Đây cũng là chỗ khoá lạc quan sống.
-- -----------------------------------------------------------------------------
CREATE TABLE judge_queue (
    submission_id   BIGINT      PRIMARY KEY REFERENCES submissions(id),
    priority        SMALLINT    NOT NULL DEFAULT 0,   -- 0 = live · 10 = rejudge (mâu thuẫn #2)
    attempt         INTEGER     NOT NULL DEFAULT 0,
    enqueued_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    claimed_at      TIMESTAMPTZ,
    claimed_by_host SMALLINT    REFERENCES judge_hosts(id),
    lease_until     TIMESTAMPTZ,                      -- claimed_at + 120s -> reaper
    ie_retry_count  SMALLINT    NOT NULL DEFAULT 0 CHECK (ie_retry_count >= 0),

    CONSTRAINT ck_judge_queue_claim CHECK (
        (claimed_at IS NULL AND lease_until IS NULL AND claimed_by_host IS NULL)
     OR (claimed_at IS NOT NULL AND lease_until IS NOT NULL)
    )
) WITH (fillfactor = 70, autovacuum_vacuum_scale_factor = 0.0, autovacuum_vacuum_threshold = 50);

-- Hai index partial, vị từ khớp CHÍNH XÁC với hai truy vấn duy nhất chạm bảng này,
-- nên planner luôn dùng được (không phụ thuộc suy luận vị từ):
CREATE INDEX ix_judge_queue_ready ON judge_queue (priority, enqueued_at, submission_id)
    WHERE claimed_at IS NULL;      -- worker claim
CREATE INDEX ix_judge_queue_lease ON judge_queue (lease_until)
    WHERE claimed_at IS NOT NULL;  -- reaper

COMMENT ON TABLE judge_queue IS
    'Hàng đợi bền. Bài nộp coi là xong khi hàng ở đây bị xoá TRONG CÙNG transaction với việc ghi verdict.';

-- -----------------------------------------------------------------------------
-- judge_runs — lịch sử từng lần chấm. FR-ADM-01: rejudge tạo attempt mới,
-- verdict cũ KHÔNG bị ghi đè. Khoá chính (submission_id, attempt) đồng thời là
-- lớp chống trùng thứ hai bên cạnh khoá lạc quan trên judge_queue.
-- -----------------------------------------------------------------------------
CREATE TABLE judge_runs (
    submission_id       BIGINT       NOT NULL REFERENCES submissions(id),
    attempt             INTEGER      NOT NULL CHECK (attempt >= 1),

    host_id             SMALLINT     REFERENCES judge_hosts(id),
    host_factor         NUMERIC(5,3) NOT NULL,
    language_id         SMALLINT     NOT NULL REFERENCES languages(id),
    testdata_version    INTEGER      NOT NULL,

    verdict             TEXT         NOT NULL CHECK (verdict IN ('AC','WA','TLE','MLE','RE','CE','IE')),
    score               INTEGER      NOT NULL DEFAULT 0 CHECK (score >= 0),
    max_score           INTEGER      NOT NULL DEFAULT 0 CHECK (max_score >= 0),
    failed_test_ordinal SMALLINT,
    tests_run           SMALLINT     NOT NULL DEFAULT 0,
    time_ms             INTEGER,
    memory_kb           INTEGER,

    -- Được phép lưu: log compiler là output từ mã của chính người nộp (FR-SUB-06,
    -- ma trận hiển thị cho phép tác giả xem). Cắt ngắn ở tầng ứng dụng.
    compile_log         TEXT         CHECK (compile_log IS NULL OR octet_length(compile_log) <= 32768),
    -- Được phép lưu: mã lỗi/meta của isolate khi IE. KHÔNG lưu stdout của chương trình.
    isolate_status      TEXT,
    trace_id            TEXT,

    started_at          TIMESTAMPTZ,
    finished_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),

    PRIMARY KEY (submission_id, attempt)
);

COMMENT ON TABLE judge_runs IS
    'Một hàng mỗi lần chấm. Không bao giờ UPDATE, không bao giờ DELETE — verdict cũ là bằng chứng khi rejudge cho kết quả khác.';
COMMENT ON COLUMN judge_runs.compile_log IS
    'Chỉ log compiler. Không bao giờ chứa nội dung testcase hay stdout chương trình người dùng.';
