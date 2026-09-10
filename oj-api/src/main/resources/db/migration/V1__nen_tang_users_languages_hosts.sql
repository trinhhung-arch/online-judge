-- V1: users,languages,judge_hosts,system_settings

-- Quy ước toàn bộ schema:
--    TIMESTAMPTZ ở mọi nơi, DB chạy UTC. Không dùng TIMESTAMP trần.

--    Khoá chính BIGINT IDENTITY, không dùng UUID để cho bảng nóng cần chèn tuần tự
--    để trang B-tree không bị phân mảnh, và cursor phân trang cần khoá tăng dần.

--    Enum biểu diễn bằng TEXT + CHECK, không dùng CREATE TYPE ... AS ENUM:
--    thêm giá trị mới chỉ là một migration ALTER ... CHECK, không vướng
--    ràng buộc "ALTER TYPE không chạy trong transaction" của Flyway.

--    Trigger của function set_updated_at() dùng chung cho các bảng nguội (users, problems, contests...) 
--    và trigger này sẽ ko có trên 'submissions' vì đó là bảng nóng bởi bảng này cập nhật liên tục 
--    nên trigger được gọi liên tục sẽ làm chậm máy,

CREATE FUNCTION set_updated_at() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;

CREATE TABLE users (
    id                  BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    handle              TEXT        NOT NULL,
    email               TEXT,                       --không để not null bởi vì sau sẽ có chức năng xoá
    password_hash       TEXT,                       --BCrypt cost 12; không để not null bởi vì sau sẽ có chức năng xoá
    display_name        TEXT        NOT NULL,
    role                TEXT        NOT NULL DEFAULT 'USER'
                                    CHECK (role IN ('USER','SETTER','ADMIN')),
    status              TEXT        NOT NULL DEFAULT 'ACTIVE'
                                    CHECK (status IN ('ACTIVE','DISABLED','ANONYMIZED')),
    preferred_language_id SMALLINT,                 
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- ràng buộc khiến cho tài khoản bị xoá bắt buộc phải ko còn dữ liệu định danh
    CONSTRAINT ck_users_anonymized CHECK (
        status <> 'ANONYMIZED' OR (email IS NULL AND password_hash IS NULL)
    ),
    -- format lại handle
    CONSTRAINT ck_users_handle_format CHECK (handle ~ '^[A-Za-z0-9_.-]{3,32}$')
);


CREATE UNIQUE INDEX ux_users_handle_lower ON users (lower(handle));
CREATE UNIQUE INDEX ux_users_email_lower  ON users (lower(email)) WHERE email IS NOT NULL;


CREATE TRIGGER trg_users_updated_at BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();



COMMENT ON TABLE  users IS 'Người dùng sẽ không xoá cứng,cre ở:FR-AUTH-07.';
COMMENT ON COLUMN users.password_hash IS 'Không bao giờ được log cột này.';

--Nếu thêm 1 ngôn ngữ chấm = 1 dòng config, 0 dòng code

CREATE TABLE languages (
    id                     SMALLINT     GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code                   TEXT         NOT NULL UNIQUE,   -- 'cpp20', 'py311', 'java21'
    display_name           TEXT         NOT NULL,
    version_label          TEXT         NOT NULL,          -- 'GCC 13.2 / C++20'
    source_extension       TEXT         NOT NULL,          -- 'cpp', 'py', 'java'
    compile_command        TEXT,                           -- NULL = ngôn ngữ thông dịch
    run_command            TEXT         NOT NULL,
    compile_time_limit_ms  INTEGER      NOT NULL DEFAULT 10000,
    compile_memory_kb      INTEGER      NOT NULL DEFAULT 1048576,
    -- Hệ số thời gian theo ngôn ngữ:(ví dụ thôi) C++ x1 · Java x2-3 · Python x3-5
    -- (cre:nfrplan 9.2). Giới hạn thực tế = problems.time_limit_ms
    --                                    * languages.time_multiplier
    --                                    * judge_hosts.host_factor
    time_multiplier        NUMERIC(4,2) NOT NULL DEFAULT 1.00 CHECK (time_multiplier > 0),
    -- VD:JVM khởi động tầm 100ms thì nó sẽ cộng thẳng vào giới hạn để bài Java không bị thiệt do nó chạy chậm hơn c++
    startup_overhead_ms    SMALLINT     NOT NULL DEFAULT 0 CHECK (startup_overhead_ms >= 0),
    memory_overhead_kb     INTEGER      NOT NULL DEFAULT 0 CHECK (memory_overhead_kb >= 0),
    enabled                BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order             SMALLINT     NOT NULL DEFAULT 100,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TRIGGER trg_languages_updated_at BEFORE UPDATE ON languages
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE users
    ADD CONSTRAINT fk_users_preferred_language
    FOREIGN KEY (preferred_language_id) REFERENCES languages(id);

-- judge_hosts và host_benchmarks để hiệu chuẩn máy chấm (cre:nfrplan 9.1) và phát hiện throttle nhiệt giữa contest.
-- host_factor thiếu nó thì sẽ "máy tao AC mà CI báo TLE".
-- judge_hosts là các máy chấm
CREATE TABLE judge_hosts (
    id            SMALLINT     GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name          TEXT         NOT NULL UNIQUE,
    arch          TEXT         NOT NULL CHECK (arch IN ('arm64','amd64')),
    judge_slots   SMALLINT     NOT NULL CHECK (judge_slots BETWEEN 1 AND 32),
    host_factor   NUMERIC(5,3) NOT NULL DEFAULT 1.000 CHECK (host_factor > 0),
    is_reference  BOOLEAN      NOT NULL DEFAULT FALSE,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    last_seen_at  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Chỉ được có đúng một máy chấm chuẩn và mọi máy khác đều quy chiếu về máy này (cre:FR-SUB-11).
CREATE UNIQUE INDEX ux_judge_hosts_single_reference ON judge_hosts ((is_reference)) WHERE is_reference;

CREATE TRIGGER trg_judge_hosts_updated_at BEFORE UPDATE ON judge_hosts
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE host_benchmarks (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    host_id     SMALLINT     NOT NULL REFERENCES judge_hosts(id),
    measured_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    host_factor NUMERIC(5,3) NOT NULL,
    drift_pct   NUMERIC(6,2),          -- so với lần đo chuẩn; alert khi > 8%
    note        TEXT
);
CREATE INDEX ix_host_benchmarks_host_time ON host_benchmarks (host_id, measured_at DESC);

-- system_settings — CÔNG TẮC LÚC ĐANG CHẠY, không phải cấu hình.
-- Cái này khác với config trong application.yml (cre:CLAUDE.md mục 7):
-- những giá trị mang tính quy tắc kỹ thuật, ít thay đổi, thì để trong application.yml
-- VD:Ngưỡng/timeout/giới hạn cố định thì để trong đó


CREATE TABLE system_settings (
    key        TEXT        PRIMARY KEY,
    value      JSONB       NOT NULL,
    updated_by BIGINT      REFERENCES users(id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO system_settings (key, value) VALUES
    ('submissions.accepting',  'true'::jsonb),   -- FR-ADM-06 chế độ bảo trì
    ('ai_review.enabled',      'false'::jsonb),  -- FR-AI-09 kill switch
    ('rejudge.enabled',        'true'::jsonb);
