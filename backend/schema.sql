-- ============================================================
-- Online Judge - Database Schema (PostgreSQL 16)
-- Giai doan 1-2: du cho OJT Fall 2026
-- ============================================================

-- ------------------------------------------------------------
-- 1. USERS - nguoi dung he thong
-- ------------------------------------------------------------
CREATE TABLE users (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(50)  NOT NULL UNIQUE,
    email           VARCHAR(255) NOT NULL UNIQUE,
    password_hash   VARCHAR(255) NOT NULL,
    full_name       VARCHAR(100),
    role            VARCHAR(20)  NOT NULL DEFAULT 'USER',
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_users_role CHECK (role IN ('USER', 'SETTER', 'ADMIN'))
);

CREATE INDEX idx_users_username ON users (username);
CREATE INDEX idx_users_email    ON users (email);


-- ------------------------------------------------------------
-- 2. PROBLEMS - de bai
-- ------------------------------------------------------------
CREATE TABLE problems (
    id              BIGSERIAL PRIMARY KEY,
    slug            VARCHAR(100) NOT NULL UNIQUE,
    title           VARCHAR(200) NOT NULL,
    description     TEXT         NOT NULL,
    input_format    TEXT,
    output_format   TEXT,
    constraints     TEXT,

    time_limit_ms   INT          NOT NULL DEFAULT 1000,
    memory_limit_kb INT          NOT NULL DEFAULT 262144,

    difficulty      VARCHAR(20)  NOT NULL DEFAULT 'MEDIUM',
    author_id       BIGINT       NOT NULL,
    is_public       BOOLEAN      NOT NULL DEFAULT FALSE,

    total_submissions    INT NOT NULL DEFAULT 0,
    accepted_submissions INT NOT NULL DEFAULT 0,

    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_problems_author
        FOREIGN KEY (author_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT chk_problems_difficulty
        CHECK (difficulty IN ('EASY', 'MEDIUM', 'HARD')),
    CONSTRAINT chk_problems_time_limit
        CHECK (time_limit_ms > 0 AND time_limit_ms <= 10000),
    CONSTRAINT chk_problems_memory_limit
        CHECK (memory_limit_kb > 0)
);

CREATE INDEX idx_problems_slug       ON problems (slug);
CREATE INDEX idx_problems_public     ON problems (is_public) WHERE is_public = TRUE;
CREATE INDEX idx_problems_difficulty ON problems (difficulty);


-- ------------------------------------------------------------
-- 3. TESTCASES - bo test cua tung bai
-- ------------------------------------------------------------
CREATE TABLE testcases (
    id              BIGSERIAL PRIMARY KEY,
    problem_id      BIGINT  NOT NULL,
    ordinal         INT     NOT NULL,
    input_data      TEXT    NOT NULL,
    expected_output TEXT    NOT NULL,
    is_sample       BOOLEAN NOT NULL DEFAULT FALSE,
    points          INT     NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_testcases_problem
        FOREIGN KEY (problem_id) REFERENCES problems (id) ON DELETE CASCADE,
    CONSTRAINT uq_testcases_problem_ordinal
        UNIQUE (problem_id, ordinal),
    CONSTRAINT chk_testcases_points CHECK (points >= 0)
);

CREATE INDEX idx_testcases_problem ON testcases (problem_id);


-- ------------------------------------------------------------
-- 4. SUBMISSIONS - bai nop
-- ------------------------------------------------------------
CREATE TABLE submissions (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT      NOT NULL,
    problem_id      BIGINT      NOT NULL,

    language        VARCHAR(20) NOT NULL,
    source_code     TEXT        NOT NULL,

    status          VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    score           INT         NOT NULL DEFAULT 0,
    total_time_ms   INT,
    max_memory_kb   INT,
    compile_error   TEXT,

    submitted_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    judged_at       TIMESTAMPTZ,

    CONSTRAINT fk_submissions_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_submissions_problem
        FOREIGN KEY (problem_id) REFERENCES problems (id) ON DELETE CASCADE,
    CONSTRAINT chk_submissions_language
        CHECK (language IN ('CPP', 'JAVA', 'PYTHON')),
    CONSTRAINT chk_submissions_status
        CHECK (status IN (
            'PENDING', 'JUDGING',
            'ACCEPTED', 'WRONG_ANSWER',
            'TIME_LIMIT_EXCEEDED', 'MEMORY_LIMIT_EXCEEDED',
            'RUNTIME_ERROR', 'COMPILE_ERROR', 'SYSTEM_ERROR'
        ))
);

CREATE INDEX idx_submissions_user       ON submissions (user_id, submitted_at DESC);
CREATE INDEX idx_submissions_problem    ON submissions (problem_id, submitted_at DESC);
CREATE INDEX idx_submissions_status     ON submissions (status) WHERE status IN ('PENDING', 'JUDGING');
CREATE INDEX idx_submissions_user_prob  ON submissions (user_id, problem_id, status);


-- ------------------------------------------------------------
-- 5. SUBMISSION_RESULTS - ket qua tung testcase
-- ------------------------------------------------------------
CREATE TABLE submission_results (
    id              BIGSERIAL PRIMARY KEY,
    submission_id   BIGINT      NOT NULL,
    testcase_id     BIGINT      NOT NULL,

    verdict         VARCHAR(30) NOT NULL,
    time_ms         INT,
    memory_kb       INT,
    error_message   TEXT,

    CONSTRAINT fk_results_submission
        FOREIGN KEY (submission_id) REFERENCES submissions (id) ON DELETE CASCADE,
    CONSTRAINT fk_results_testcase
        FOREIGN KEY (testcase_id) REFERENCES testcases (id) ON DELETE CASCADE,
    CONSTRAINT uq_results_submission_testcase
        UNIQUE (submission_id, testcase_id),
    CONSTRAINT chk_results_verdict
        CHECK (verdict IN (
            'ACCEPTED', 'WRONG_ANSWER',
            'TIME_LIMIT_EXCEEDED', 'MEMORY_LIMIT_EXCEEDED',
            'RUNTIME_ERROR', 'SKIPPED'
        ))
);

CREATE INDEX idx_results_submission ON submission_results (submission_id);


-- ------------------------------------------------------------
-- 6. TAGS + PROBLEM_TAGS - phan loai bai (quan he nhieu-nhieu)
-- ------------------------------------------------------------
CREATE TABLE tags (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(50) NOT NULL UNIQUE,
    slug        VARCHAR(50) NOT NULL UNIQUE
);

CREATE TABLE problem_tags (
    problem_id  BIGINT NOT NULL,
    tag_id      BIGINT NOT NULL,

    PRIMARY KEY (problem_id, tag_id),
    CONSTRAINT fk_problem_tags_problem
        FOREIGN KEY (problem_id) REFERENCES problems (id) ON DELETE CASCADE,
    CONSTRAINT fk_problem_tags_tag
        FOREIGN KEY (tag_id) REFERENCES tags (id) ON DELETE CASCADE
);

CREATE INDEX idx_problem_tags_tag ON problem_tags (tag_id);


-- ============================================================
-- DU LIEU MAU
-- ============================================================

INSERT INTO tags (name, slug) VALUES
    ('Graph',            'graph'),
    ('Dynamic Programming', 'dp'),
    ('Greedy',           'greedy'),
    ('Binary Search',    'binary-search'),
    ('Data Structures',  'data-structures'),
    ('Math',             'math'),
    ('String',           'string'),
    ('Implementation',   'implementation');

-- password_hash duoi day la BCrypt cua chuoi "password123"
INSERT INTO users (username, email, password_hash, full_name, role) VALUES
    ('admin',  'admin@oj.local',  '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'Administrator', 'ADMIN'),
    ('setter', 'setter@oj.local', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'Problem Setter', 'SETTER'),
    ('hung',   'hung@oj.local',   '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'Trinh Quoc Khanh Hung', 'USER');

INSERT INTO problems (slug, title, description, input_format, output_format, constraints,
                      time_limit_ms, memory_limit_kb, difficulty, author_id, is_public)
VALUES (
    'a-plus-b',
    'A + B',
    'Cho hai so nguyen A va B. Hay tinh tong cua chung.',
    'Mot dong duy nhat chua hai so nguyen A va B, cach nhau boi dau cach.',
    'In ra mot so nguyen duy nhat la tong A + B.',
    '-10^9 <= A, B <= 10^9',
    1000, 262144, 'EASY', 2, TRUE
);

INSERT INTO testcases (problem_id, ordinal, input_data, expected_output, is_sample, points) VALUES
    (1, 1, '1 2',            '3',            TRUE,  1),
    (1, 2, '100 200',        '300',          TRUE,  1),
    (1, 3, '-5 5',           '0',            FALSE, 1),
    (1, 4, '1000000000 1000000000', '2000000000', FALSE, 1);

INSERT INTO problem_tags (problem_id, tag_id) VALUES
    (1, 6),
    (1, 8);