-- =============================================================================
-- V8 — AI Code Reviewer
-- Mốc: tuần 14-15 (phương án C, nfrplan 10.7). FR-AI-01..09.
--
-- Ràng buộc kiến trúc AI1: 0ms thêm vào đường chấm. Ở tầng dữ liệu điều đó
-- nghĩa là KHÔNG cột nào của `submissions` bị AI ghi vào — quan hệ đi một chiều
-- từ ai_reviews sang submissions, không có chiều ngược.
-- =============================================================================

CREATE TABLE ai_reviews (
    id              BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    -- FR-AI-06 + Quy tắc 4: cùng một submission KHÔNG BAO GIỜ gọi LLM lần hai.
    -- UNIQUE ở đây là thứ thực thi điều đó, không phải một câu if trong service.
    submission_id   BIGINT      NOT NULL UNIQUE REFERENCES submissions(id),
    user_id         BIGINT      NOT NULL REFERENCES users(id),
    problem_id      BIGINT      NOT NULL REFERENCES problems(id),
    source_sha256   CHAR(64)    NOT NULL,

    status          TEXT        NOT NULL DEFAULT 'PENDING'
                                CHECK (status IN ('PENDING','STREAMING','DONE','FAILED')),
    content_md      TEXT,       -- Markdown; render có sanitize + CSP (nfrplan 10.2)
    failure_reason  TEXT,

    -- nfrplan 10.6: lưu model + prompt_version cùng mỗi review, để khi chất
    -- lượng đột nhiên tệ đi thì truy được nguyên nhân.
    model           TEXT,
    prompt_version  TEXT,
    input_tokens    INTEGER,
    output_tokens   INTEGER,
    cost_micro_usd  INTEGER,    -- số nguyên micro-USD, không dùng float cho tiền

    -- Cache theo sha256(source): cùng code, cùng đề -> dùng lại review cũ,
    -- không gọi LLM, không trừ quota (nfrplan 10.3).
    reused_from_id  BIGINT      REFERENCES ai_reviews(id),

    feedback        SMALLINT    CHECK (feedback IN (-1, 1)),   -- FR-AI-07
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ,

    CONSTRAINT ck_ai_reviews_done CHECK (status <> 'DONE' OR content_md IS NOT NULL)
);

CREATE INDEX ix_ai_reviews_cache ON ai_reviews (problem_id, source_sha256)
    WHERE status = 'DONE';
CREATE INDEX ix_ai_reviews_user ON ai_reviews (user_id, id DESC);

-- -----------------------------------------------------------------------------
-- Quota 5 review/ngày/user (FR-AI-03). Bảng này nhỏ và tồn tại để việc trừ quota
-- là MỘT câu lệnh nguyên tử — xem mục "Quota" trong postgres-design.md.
-- Đếm quota bằng COUNT(*) trên ai_reviews là sai: có review không trừ quota
-- (bản dùng lại cache, bản LLM lỗi theo FR-AI-08).
-- -----------------------------------------------------------------------------
CREATE TABLE ai_quota_usage (
    user_id    BIGINT   NOT NULL REFERENCES users(id),
    usage_date DATE     NOT NULL,
    used_count SMALLINT NOT NULL DEFAULT 0 CHECK (used_count >= 0),
    PRIMARY KEY (user_id, usage_date)
);

-- Dashboard chi phí + alert ở 80% ngân sách (AI2, nfrplan 10.3)
CREATE TABLE ai_usage_daily (
    usage_date     DATE   PRIMARY KEY,
    call_count     INTEGER NOT NULL DEFAULT 0,
    input_tokens   BIGINT  NOT NULL DEFAULT 0,
    output_tokens  BIGINT  NOT NULL DEFAULT 0,
    cost_micro_usd BIGINT  NOT NULL DEFAULT 0,
    failure_count  INTEGER NOT NULL DEFAULT 0
);
