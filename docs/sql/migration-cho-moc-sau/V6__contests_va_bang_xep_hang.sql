-- =============================================================================
-- V6 — Kỳ thi và bảng xếp hạng
-- Mốc: M5 (tuần 10-12). FR-CON-01..09.
--
-- Nguyên tắc: REDIS LÀ CACHE, POSTGRES LÀ SỰ THẬT (oj-api/CLAUDE.md mục 6).
-- Mọi giá trị trong Redis phải dựng lại được 100% từ các bảng dưới đây, và
-- `ix_contest_standings_rank` tồn tại để khi Redis chết thì đọc thẳng Postgres
-- vẫn ra đúng thứ hạng — chậm hơn, nhưng đúng (degraded mode, nfrplan 7.2).
-- =============================================================================

CREATE TABLE contests (
    id                     BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    slug                   TEXT        NOT NULL,
    title                  TEXT        NOT NULL,
    format                 TEXT        NOT NULL CHECK (format IN ('ICPC','IOI')),
    starts_at              TIMESTAMPTZ NOT NULL,
    ends_at                TIMESTAMPTZ NOT NULL,
    freeze_at              TIMESTAMPTZ,            -- NULL = không đóng băng
    unfrozen_at            TIMESTAMPTZ,            -- ADMIN công bố -> bảng đầy đủ
    penalty_minutes        SMALLINT    NOT NULL DEFAULT 20 CHECK (penalty_minutes >= 0),
    registration_required  BOOLEAN     NOT NULL DEFAULT TRUE,
    reveal_after_end       BOOLEAN     NOT NULL DEFAULT TRUE,  -- FR-CON-07
    created_by             BIGINT      NOT NULL REFERENCES users(id),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_contest_window CHECK (ends_at > starts_at),
    CONSTRAINT ck_contest_freeze CHECK (
        freeze_at IS NULL OR (freeze_at > starts_at AND freeze_at <= ends_at))
);
CREATE UNIQUE INDEX ux_contests_slug_lower ON contests (lower(slug));
-- Truy vấn "có contest nào đang diễn ra không" chạy ở RẤT nhiều use-case
-- (FR-PROB-11 cấm sửa đề, FR-AI-02 tắt AI, FR-ADM-01 cấm rejudge).
CREATE INDEX ix_contests_window ON contests (starts_at, ends_at);

CREATE TRIGGER trg_contests_updated_at BEFORE UPDATE ON contests
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE contest_problems (
    contest_id BIGINT   NOT NULL REFERENCES contests(id) ON DELETE CASCADE,
    problem_id BIGINT   NOT NULL REFERENCES problems(id),
    label      TEXT     NOT NULL,                    -- 'A', 'B', ...
    ordinal    SMALLINT NOT NULL,
    points     INTEGER  NOT NULL DEFAULT 100 CHECK (points > 0),   -- dùng cho IOI
    PRIMARY KEY (contest_id, problem_id),
    UNIQUE (contest_id, label)
);
-- Trả lời "đề này có đang nằm trong contest nào không" (FR-PROB-11) trong 1 index scan
CREATE INDEX ix_contest_problems_problem ON contest_problems (problem_id, contest_id);

CREATE TABLE contest_registrations (
    contest_id    BIGINT      NOT NULL REFERENCES contests(id) ON DELETE CASCADE,
    user_id       BIGINT      NOT NULL REFERENCES users(id),
    registered_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (contest_id, user_id)
);

-- -----------------------------------------------------------------------------
-- Bảng xếp hạng đã denormalize. Cập nhật theo lô mỗi 2 giây (FR-CON-04, P8),
-- không phải mỗi verdict.
-- -----------------------------------------------------------------------------
CREATE TABLE contest_standings (
    contest_id         BIGINT      NOT NULL REFERENCES contests(id) ON DELETE CASCADE,
    user_id            BIGINT      NOT NULL REFERENCES users(id),
    total_score        INTEGER     NOT NULL DEFAULT 0,
    penalty_seconds    INTEGER     NOT NULL DEFAULT 0,
    solved_count       SMALLINT    NOT NULL DEFAULT 0,
    last_scoring_at    TIMESTAMPTZ,
    -- watermark: submission_id lớn nhất đã được tính vào hàng này.
    -- Vừa làm cho việc cập nhật lô trở nên idempotent (Quy tắc 4), vừa là đầu
    -- vào của job đối soát drift (FR-CON-09).
    last_applied_submission_id BIGINT NOT NULL DEFAULT 0,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (contest_id, user_id)
);

-- Thứ tự xếp hạng: điểm giảm dần, penalty tăng dần, ai đạt trước xếp trên.
CREATE INDEX ix_contest_standings_rank
    ON contest_standings (contest_id, total_score DESC, penalty_seconds ASC, last_scoring_at ASC);

CREATE TABLE contest_problem_standings (
    contest_id            BIGINT      NOT NULL,
    user_id               BIGINT      NOT NULL,
    problem_id            BIGINT      NOT NULL,
    best_score            INTEGER     NOT NULL DEFAULT 0,
    attempts_before_score SMALLINT    NOT NULL DEFAULT 0,
    first_solved_at       TIMESTAMPTZ,
    solved_submission_id  BIGINT,
    last_applied_submission_id BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (contest_id, user_id, problem_id),
    FOREIGN KEY (contest_id, user_id)
        REFERENCES contest_standings(contest_id, user_id) ON DELETE CASCADE
);

-- -----------------------------------------------------------------------------
-- Đóng băng (FR-CON-05). Không sửa dữ liệu thật: chụp một bản tại thời điểm
-- freeze và phục vụ bản chụp đó cho người thường. Chi phí O(số thí sinh), một
-- lần, đúng vào lúc hệ thống bận nhất -> rẻ hơn nhiều so với lọc theo thời gian
-- trên `submissions` ở mỗi lần render bảng.
-- -----------------------------------------------------------------------------
CREATE TABLE contest_standings_frozen (
    contest_id      BIGINT      NOT NULL REFERENCES contests(id) ON DELETE CASCADE,
    user_id         BIGINT      NOT NULL REFERENCES users(id),
    total_score     INTEGER     NOT NULL,
    penalty_seconds INTEGER     NOT NULL,
    solved_count    SMALLINT    NOT NULL,
    last_scoring_at TIMESTAMPTZ,
    captured_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (contest_id, user_id)
);
CREATE INDEX ix_contest_standings_frozen_rank
    ON contest_standings_frozen (contest_id, total_score DESC, penalty_seconds ASC, last_scoring_at ASC);

CREATE TABLE contest_problem_standings_frozen (
    contest_id            BIGINT   NOT NULL,
    user_id               BIGINT   NOT NULL,
    problem_id            BIGINT   NOT NULL,
    best_score            INTEGER  NOT NULL,
    attempts_before_score SMALLINT NOT NULL,
    first_solved_at       TIMESTAMPTZ,
    -- Số bài nộp sau giờ freeze -> UI hiện ô "?" đúng kiểu ICPC
    pending_after_freeze  SMALLINT NOT NULL DEFAULT 0,
    PRIMARY KEY (contest_id, user_id, problem_id),
    FOREIGN KEY (contest_id, user_id)
        REFERENCES contest_standings_frozen(contest_id, user_id) ON DELETE CASCADE
);

-- FR-CON-09 — job đối soát dữ liệu denormalize + metric drift + alert
CREATE TABLE standings_drift_checks (
    id               BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    contest_id       BIGINT      NOT NULL REFERENCES contests(id) ON DELETE CASCADE,
    checked_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    rows_checked     INTEGER     NOT NULL,
    rows_mismatched  INTEGER     NOT NULL,
    detail           JSONB       NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX ix_standings_drift_contest ON standings_drift_checks (contest_id, checked_at DESC);

-- -----------------------------------------------------------------------------
-- Gắn khoá ngoại `submissions.contest_id` — cột đã tồn tại từ V3.
-- NOT VALID trước, VALIDATE sau: VALIDATE chỉ lấy SHARE UPDATE EXCLUSIVE nên
-- đường nộp bài không bị khoá, kể cả khi bảng đã có triệu dòng
-- (oj-api/CLAUDE.md mục 7 — thêm ràng buộc vào bảng có dữ liệu là hai bước).
-- -----------------------------------------------------------------------------
ALTER TABLE submissions
    ADD CONSTRAINT fk_submissions_contest
    FOREIGN KEY (contest_id) REFERENCES contests(id) NOT VALID;

ALTER TABLE submissions VALIDATE CONSTRAINT fk_submissions_contest;

-- CỐ Ý KHÔNG tạo index trên `submissions.contest_id`.
-- Dựng lại bảng xếp hạng (FR-CON-08) chạy theo từng đề của contest và chặn
-- khoảng id: các bài trong contest luôn nằm giữa id đầu và id cuối của khung
-- giờ thi, nên `ix_submissions_problem_recent` đã đủ:
--     WHERE problem_id = :p AND id BETWEEN :lo AND :hi AND contest_id = :c
-- Đổi lại, ngân sách index của bảng nóng vẫn còn chỗ trống.
