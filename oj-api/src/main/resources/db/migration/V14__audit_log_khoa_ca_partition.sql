-- =============================================================================
-- V14 — audit_log append-only THẬT: khoá cả partition, không chỉ bảng cha
-- Sửa lỗ tìm thấy khi rà bảo mật 2026-09-23 (docs/bao-mat-plan.md).
--
-- ★ LỖ: V8 viết `REVOKE UPDATE, DELETE, TRUNCATE ON audit_log FROM oj_app`. Nhưng
--   audit_log là bảng PHÂN VÙNG (V5): dòng thật nằm ở audit_log_YYYY_MM và
--   audit_log_default, bảng cha không chứa dòng nào. Postgres xét quyền trên ĐÚNG
--   bảng được gọi tên và KHÔNG truyền GRANT/REVOKE từ cha xuống con, nên:
--
--     DELETE FROM audit_log         WHERE ...  -> permission denied   (V8 chặn)
--     DELETE FROM audit_log_2026_09 WHERE ...  -> DELETE 1            (thủng)
--
--   Hai đường làm con mở:
--     1. `GRANT ... ON ALL TABLES` của V8 cấp cho MỌI bảng, kể cả các partition
--        đã có; `REVOKE` ngay sau đó chỉ thu lại trên bảng cha.
--     2. `ALTER DEFAULT PRIVILEGES` của V8 cấp SELECT/INSERT/UPDATE/DELETE cho mọi
--        bảng tạo SAU đó — tức mọi partition mà job hằng ngày tạo mỗi tháng.
--
--   Tái hiện 2026-09-23 trên Postgres tạm, V1-V8 + infra/postgres/init/01-roles.sql,
--   dưới `SET ROLE oj_app`: xoá và sửa được dòng qua tên partition; partition tạo
--   bằng hàm của V8 cũng xoá được. Test không bắt được vì bộ IT chạy bằng role sở
--   hữu schema, và không test nào từng đóng vai oj_app.
--
-- ★ CÁCH SỬA: partition không phải một CỬA. oj_app không có quyền GÌ trên partition
--   (REVOKE ALL, không chỉ UPDATE/DELETE). Mọi truy cập đi qua bảng cha, nơi Postgres
--   chỉ xét quyền của bảng cha: INSERT và SELECT vẫn chạy (JdbcAuditLog,
--   JdbcAuditLogReader), UPDATE/DELETE/TRUNCATE vẫn bị V8 chặn. Thu cả SELECT/INSERT
--   là để không còn "quyền trên partition" nào cho ai phải nhớ giữ đồng bộ với bảng cha.
--
-- Bằng chứng: AuditLogChiGhiThemIT — dựng role bằng đúng 01-roles.sql, chạy BẰNG oj_app.
--
-- ⚠️ CREATE OR REPLACE FUNCTION đòi role chạy Flyway phải SỞ HỮU hàm của V8. Nếu host
--    đổi role Flyway giữa chừng thì migration này hỏng ngay lúc khởi động — ồn ào, không
--    im lặng. Cùng điều kiện ấy V10 và V12 (ALTER TABLE contest_problems) đã cần.
--
-- Viết phòng thủ như V8: role oj_app chưa tồn tại (Testcontainers, máy dev mới) thì
-- bỏ qua phần REVOKE — nhưng hàm vẫn được thay, để ngày role được tạo thì partition
-- tạo từ đó trở đi đã khoá sẵn.
-- =============================================================================

-- 1. Partition đang có — mọi con của audit_log, kể cả audit_log_default.
DO $$
DECLARE
    v_con regclass;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'oj_app') THEN
        RAISE NOTICE 'Role oj_app chưa tồn tại — bỏ qua REVOKE trên partition audit_log.';
        RETURN;
    END IF;

    FOR v_con IN
        SELECT i.inhrelid::regclass
          FROM pg_inherits i
         WHERE i.inhparent = 'audit_log'::regclass
    LOOP
        -- %s với regclass: Postgres tự trích dẫn tên khi chuyển regclass sang chuỗi.
        EXECUTE format('REVOKE ALL ON %s FROM oj_app', v_con);
    END LOOP;
END;
$$;

-- 2. Partition tạo sau này — hàm tạo xong là khoá ngay, trong CÙNG transaction.
--    Thân hàm giữ nguyên V8 tới dòng CREATE TABLE; phần mới là khối cuối.
CREATE OR REPLACE FUNCTION create_audit_log_partition(p_month DATE) RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
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

    -- ALTER DEFAULT PRIVILEGES (V8) vừa cấp DML trên bảng này cho oj_app. Thu lại.
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'oj_app') THEN
        EXECUTE format('REVOKE ALL ON %I FROM oj_app', v_name);
    END IF;
END;
$$;

COMMENT ON FUNCTION create_audit_log_partition(DATE) IS
    'SECURITY DEFINER: oj_app không có DDL (V8) mà job hằng ngày phải tạo được partition. '
    'Từ V14: tạo xong là REVOKE ALL khỏi oj_app — partition không phải một cửa.';
