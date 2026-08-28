-- =============================================================================
-- V5 — Xác thực và nhật ký kiểm toán
-- Mốc: M4 (tuần 7). FR-AUTH-02..08, FR-ADM-02.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- refresh_tokens — FR-AUTH-02/03/04. Lưu SHA-256 của token, không lưu token thô:
-- lộ database không được đồng nghĩa với lộ phiên đăng nhập.
-- -----------------------------------------------------------------------------
CREATE TABLE refresh_tokens (
    id             BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id        BIGINT      NOT NULL REFERENCES users(id),
    token_sha256   CHAR(64)    NOT NULL UNIQUE,
    issued_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at     TIMESTAMPTZ NOT NULL,
    revoked_at     TIMESTAMPTZ,
    revoked_reason TEXT,
    replaced_by_id BIGINT      REFERENCES refresh_tokens(id),
    user_agent     TEXT,
    client_ip      INET,

    CONSTRAINT ck_refresh_window CHECK (expires_at > issued_at)
);

-- Partial: chỉ token còn sống mới cần tra cứu nhanh khi thu hồi hàng loạt.
CREATE INDEX ix_refresh_tokens_active ON refresh_tokens (user_id)
    WHERE revoked_at IS NULL;

-- -----------------------------------------------------------------------------
-- login_attempts — FR-AUTH-08 (5 lần sai/phút/IP, khoá 15 phút).
-- Đường chính là Redis (TTL, nhanh). Bảng này là bản ghi bền để (a) khoá tài
-- khoản vẫn còn hiệu lực sau khi Redis restart, (b) có bằng chứng cho audit.
-- -----------------------------------------------------------------------------
CREATE TABLE login_attempts (
    id           BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    handle_tried TEXT        NOT NULL,
    client_ip    INET        NOT NULL,
    succeeded    BOOLEAN     NOT NULL,
    attempted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_login_attempts_ip_recent ON login_attempts (client_ip, attempted_at DESC)
    WHERE NOT succeeded;

CREATE TABLE login_lockouts (
    client_ip    INET        PRIMARY KEY,
    locked_until TIMESTAMPTZ NOT NULL,
    reason       TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- -----------------------------------------------------------------------------
-- audit_log — APPEND-ONLY, phân mảnh theo tháng.
--
-- Vì sao bảng này PHẢI partition mà `submissions` thì KHÔNG (xem postgres-design.md):
-- audit_log chỉ ghi thêm, và mọi truy vấn đọc đều có khoảng thời gian
-- ("ai làm gì trong tháng 3") -> partition pruning ăn tiền thật. Trong khi
-- truy vấn nóng của `submissions` là "lịch sử của user X" — không giới hạn thời
-- gian — nên partition chỉ làm chậm đi.
--
-- Tính append-only KHÔNG ép bằng trigger (trigger có thể bị tắt) mà ép bằng
-- phân quyền: role ứng dụng chỉ có INSERT + SELECT. Xem V9 và mục "Phân quyền".
-- -----------------------------------------------------------------------------
CREATE SEQUENCE audit_log_id_seq AS BIGINT;

CREATE TABLE audit_log (
    id          BIGINT      NOT NULL DEFAULT nextval('audit_log_id_seq'),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor_id    BIGINT      REFERENCES users(id),   -- NULL = hệ thống (reaper, job)
    actor_role  TEXT,
    action      TEXT        NOT NULL,               -- 'PROBLEM_TESTDATA_REPLACED', 'SUBMISSION_HIDDEN', ...
    entity_type TEXT        NOT NULL,
    entity_id   BIGINT,
    detail      JSONB       NOT NULL DEFAULT '{}'::jsonb,
    trace_id    TEXT,
    client_ip   INET,
    PRIMARY KEY (occurred_at, id)
) PARTITION BY RANGE (occurred_at);

ALTER SEQUENCE audit_log_id_seq OWNED BY audit_log.id;

-- Index đặt trên bảng cha -> Postgres tự tạo trên mọi partition, cũ và mới.
CREATE INDEX ix_audit_log_actor  ON audit_log (actor_id, occurred_at DESC);
CREATE INDEX ix_audit_log_entity ON audit_log (entity_type, entity_id, occurred_at DESC);
CREATE INDEX ix_audit_log_action ON audit_log (action, occurred_at DESC);

-- Partition mặc định: không bao giờ được mất một dòng audit chỉ vì quên tạo
-- partition tháng sau. Job hàng tháng sẽ tạo partition thật và dọn DEFAULT.
CREATE TABLE audit_log_default PARTITION OF audit_log DEFAULT;

-- Hàm tạo partition cho một tháng bất kỳ; job hàng tháng gọi sẵn 2 tháng tới.
CREATE FUNCTION create_audit_log_partition(p_month DATE) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE
    v_start DATE := date_trunc('month', p_month)::date;
    v_end   DATE := (date_trunc('month', p_month) + INTERVAL '1 month')::date;
    v_name  TEXT := format('audit_log_%s', to_char(v_start, 'YYYY_MM'));
BEGIN
    IF to_regclass(v_name) IS NOT NULL THEN
        RETURN;
    END IF;
    EXECUTE format(
        'CREATE TABLE %I PARTITION OF audit_log FOR VALUES FROM (%L) TO (%L)',
        v_name, v_start, v_end);
END;
$$;

COMMENT ON TABLE audit_log IS
    'Append-only. Role ứng dụng chỉ được GRANT INSERT, SELECT. Không UPDATE, không DELETE — kể cả ADMIN.';

-- Tạo sẵn partition tháng này + 3 tháng tới. LƯU Ý: partition mới KHÔNG gắn được
-- nếu partition DEFAULT đã chứa dòng thuộc khoảng đó -> job hàng tháng phải chạy
-- TRƯỚC khi sang tháng, đây là lý do tạo dư 3 tháng.
DO $$
DECLARE v_month DATE := date_trunc('month', now())::date;
BEGIN
    FOR i IN 0..3 LOOP
        PERFORM create_audit_log_partition((v_month + (i || ' month')::interval)::date);
    END LOOP;
END;
$$;
