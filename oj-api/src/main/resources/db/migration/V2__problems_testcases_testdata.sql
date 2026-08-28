-- =============================================================================
-- V2 — Đề bài, phiên bản testdata, testcase
-- Mốc: M1 (tối giản) → M4 (đầy đủ). FR-PROB-01..12.
--
-- QUYẾT ĐỊNH QUAN TRỌNG NHẤT CỦA FILE NÀY (SEC3, bất biến #1):
--   Postgres KHÔNG LƯU nội dung testcase ẩn. Bảng `testcases` chỉ giữ
--   metadata + sha256; nội dung nằm content-addressed trên MinIO và chỉ worker
--   tải về. Nội dung testcase SAMPLE (công khai) được lưu ở bảng riêng
--   `sample_testcase_contents`, và ràng buộc khoá ngoại tổng hợp làm cho việc
--   lưu nhầm một testcase ẩn vào đó là BẤT KHẢ THI Ở TẦNG SCHEMA — không phụ
--   thuộc vào việc lập trình viên có nhớ viết `if (isSample)` hay không.
-- =============================================================================

CREATE TABLE problems (
    id                      BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code                    TEXT        NOT NULL,
    title                   TEXT        NOT NULL,
    statement_md            TEXT        NOT NULL,
    -- sha256(statement_md) — khoá cache bản render Markdown+LaTeX (FR-PROB-02)
    statement_hash          CHAR(64)    NOT NULL,

    time_limit_ms           INTEGER     NOT NULL CHECK (time_limit_ms BETWEEN 100 AND 30000),
    memory_limit_kb         INTEGER     NOT NULL CHECK (memory_limit_kb BETWEEN 16384 AND 1048576),
    output_limit_kb         INTEGER     NOT NULL DEFAULT 65536 CHECK (output_limit_kb > 0),

    checker_type            TEXT        NOT NULL DEFAULT 'token'
                                        CHECK (checker_type IN ('exact','token','float')),
    checker_epsilon         NUMERIC,
    scoring_mode            TEXT        NOT NULL DEFAULT 'ALL_OR_NOTHING'
                                        CHECK (scoring_mode IN ('ALL_OR_NOTHING','SUBTASK')),

    -- FR-PROB-07 — biện pháp chống rò rỉ testdata, KHÔNG phải tính năng.
    -- Mặc định TEST_INDEX: mức an toàn nhất mà vẫn dùng được để luyện tập.
    feedback_level          TEXT        NOT NULL DEFAULT 'TEST_INDEX'
                                        CHECK (feedback_level IN ('NONE','TEST_INDEX','SAMPLE_DETAIL')),

    -- Cho phép xem source đã AC của người khác (mâu thuẫn #9) — mặc định TẮT.
    allow_public_solutions  BOOLEAN     NOT NULL DEFAULT FALSE,

    status                  TEXT        NOT NULL DEFAULT 'DRAFT'
                                        CHECK (status IN ('DRAFT','PUBLISHED','RETIRED')),
    owner_id                BIGINT      NOT NULL REFERENCES users(id),
    current_testdata_version INTEGER    NOT NULL DEFAULT 0,
    published_at            TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- epsilon chỉ có nghĩa với checker float, và bắt buộc phải có khi là float
    CONSTRAINT ck_problems_epsilon CHECK ((checker_type = 'float') = (checker_epsilon IS NOT NULL)),
    CONSTRAINT ck_problems_published CHECK (status <> 'PUBLISHED' OR published_at IS NOT NULL),
    CONSTRAINT ck_problems_code CHECK (code ~ '^[A-Za-z0-9_-]{2,32}$')
);

CREATE UNIQUE INDEX ux_problems_code_lower ON problems (lower(code));
-- Danh sách đề công khai (FR-PROB-09) — partial, không cõng đề DRAFT/RETIRED
CREATE INDEX ix_problems_published ON problems (id DESC) WHERE status = 'PUBLISHED';
CREATE INDEX ix_problems_owner ON problems (owner_id, id DESC);

CREATE TRIGGER trg_problems_updated_at BEFORE UPDATE ON problems
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON COLUMN problems.feedback_level IS
    'FR-PROB-07. NONE=chỉ verdict · TEST_INDEX=chỉ số thứ tự test · SAMPLE_DETAIL=chi tiết nhưng CHỈ với test sample. Không có mức nào cho phép lộ nội dung test ẩn.';

-- -----------------------------------------------------------------------------
-- Tag đề bài
-- -----------------------------------------------------------------------------
CREATE TABLE tags (
    id   SMALLINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    slug TEXT     NOT NULL UNIQUE,
    name TEXT     NOT NULL
);

CREATE TABLE problem_tags (
    problem_id BIGINT   NOT NULL REFERENCES problems(id) ON DELETE CASCADE,
    tag_id     SMALLINT NOT NULL REFERENCES tags(id),
    PRIMARY KEY (problem_id, tag_id)
);
CREATE INDEX ix_problem_tags_tag ON problem_tags (tag_id, problem_id);

-- -----------------------------------------------------------------------------
-- testdata_versions — mấu chốt của FR-PROB-10 / mâu thuẫn #12.
-- Sửa testdata KHÔNG ghi đè: nó tạo version mới. Mỗi lần chấm ghi lại version
-- đã dùng, nên khi verdict hôm nay khác hôm qua thì truy được ngay vì sao.
-- -----------------------------------------------------------------------------
CREATE TABLE testdata_versions (
    problem_id      BIGINT      NOT NULL REFERENCES problems(id) ON DELETE CASCADE,
    version         INTEGER     NOT NULL CHECK (version >= 1),
    manifest_sha256 CHAR(64)    NOT NULL,   -- worker cache theo hash này
    test_count      SMALLINT    NOT NULL CHECK (test_count BETWEEN 1 AND 1000),
    total_bytes     BIGINT      NOT NULL CHECK (total_bytes <= 2147483648),  -- <= 2GB sau giải nén
    created_by      BIGINT      NOT NULL REFERENCES users(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    note            TEXT,
    PRIMARY KEY (problem_id, version)
);

-- -----------------------------------------------------------------------------
-- testcases — CHỈ METADATA. Không có cột nội dung. Cố ý.
-- -----------------------------------------------------------------------------
CREATE TABLE testcases (
    id               BIGINT   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    problem_id       BIGINT   NOT NULL,
    testdata_version INTEGER  NOT NULL,
    ordinal          SMALLINT NOT NULL CHECK (ordinal BETWEEN 1 AND 1000),
    is_sample        BOOLEAN  NOT NULL DEFAULT FALSE,
    input_sha256     CHAR(64) NOT NULL,
    output_sha256    CHAR(64) NOT NULL,
    input_bytes      INTEGER  NOT NULL CHECK (input_bytes >= 0),
    output_bytes     INTEGER  NOT NULL CHECK (output_bytes >= 0),

    FOREIGN KEY (problem_id, testdata_version)
        REFERENCES testdata_versions(problem_id, version) ON DELETE CASCADE,
    UNIQUE (problem_id, testdata_version, ordinal),
    -- cần cho khoá ngoại tổng hợp ở bảng dưới
    UNIQUE (id, is_sample)
);

COMMENT ON TABLE testcases IS
    'CHỈ metadata. Nội dung test nằm trên MinIO theo sha256 và chỉ oj-worker tải. Thêm cột nội dung vào bảng này là vi phạm bất biến #1 (SEC3).';

-- Nội dung của testcase CÔNG KHAI. Khoá ngoại tổng hợp (testcase_id, is_sample)
-- cộng với CHECK(is_sample) khiến một testcase ẩn KHÔNG THỂ có hàng ở đây.
CREATE TABLE sample_testcase_contents (
    testcase_id  BIGINT  PRIMARY KEY,
    is_sample    BOOLEAN NOT NULL DEFAULT TRUE CHECK (is_sample),
    input_text   TEXT    NOT NULL CHECK (octet_length(input_text)  <= 65536),
    output_text  TEXT    NOT NULL CHECK (octet_length(output_text) <= 65536),
    explanation  TEXT,

    FOREIGN KEY (testcase_id, is_sample) REFERENCES testcases (id, is_sample) ON DELETE CASCADE
);

COMMENT ON TABLE sample_testcase_contents IS
    'Chỉ chứa test đã đánh dấu sample. Ràng buộc FK tổng hợp làm việc lưu nhầm test ẩn trở nên bất khả thi ở tầng schema, không phụ thuộc code ứng dụng.';

-- -----------------------------------------------------------------------------
-- Cache bản render đề bài (FR-PROB-02, P1). Để trong Postgres chứ không chỉ
-- Redis: Redis chết thì trang đề vẫn phải mở được (degraded mode, nfrplan 7.2).
-- -----------------------------------------------------------------------------
CREATE TABLE rendered_statements (
    statement_hash   CHAR(64)    NOT NULL,
    renderer_version TEXT        NOT NULL,
    html             TEXT        NOT NULL,
    rendered_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (statement_hash, renderer_version)
);
