-- =============================================================================
-- V9 — Phân quyền ở tầng database
-- Mốc: M6 (cùng lúc siết vận hành).
--
-- Ý tưởng: Flyway chạy bằng role SỞ HỮU schema (oj_migrator), ứng dụng chạy
-- bằng role KHÁC (oj_app) không có quyền DDL. Lợi ích cụ thể, không phải hình thức:
--   * `audit_log` append-only trở thành ép buộc thật (REVOKE UPDATE/DELETE),
--     không phải một quy ước mà một câu lệnh sai là phá được.
--   * `judge_runs` không sửa được -> lịch sử verdict là bằng chứng, không phải ghi chú.
--   * Một lỗ SQL injection lọt lưới cũng không DROP được bảng.
--
-- File này viết phòng thủ: nếu role chưa tồn tại thì bỏ qua, để migration vẫn
-- chạy được trên máy dev và trong Testcontainers mà không cần dựng role trước.
-- =============================================================================

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'oj_app') THEN
        RAISE NOTICE 'Role oj_app chưa tồn tại — bỏ qua phần GRANT. Trên host thật phải tạo role trước.';
        RETURN;
    END IF;

    EXECUTE 'GRANT USAGE ON SCHEMA public TO oj_app';
    EXECUTE 'GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO oj_app';
    EXECUTE 'GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO oj_app';

    -- Append-only thật sự
    EXECUTE 'REVOKE UPDATE, DELETE, TRUNCATE ON audit_log FROM oj_app';

    -- Lịch sử chấm là bất biến: chỉ thêm, không sửa, không xoá
    EXECUTE 'REVOKE UPDATE, DELETE, TRUNCATE ON judge_runs FROM oj_app';
    EXECUTE 'REVOKE UPDATE, DELETE, TRUNCATE ON judge_run_subtasks FROM oj_app';

    -- FR-SUB-09: không ai được xoá bài nộp, kể cả ADMIN, kể cả qua SQL
    EXECUTE 'REVOKE DELETE, TRUNCATE ON submissions FROM oj_app';
    EXECUTE 'REVOKE DELETE, TRUNCATE ON source_blobs FROM oj_app';

    -- Bảng tham chiếu chỉ đọc từ phía ứng dụng; đổi ngôn ngữ/máy chấm đi qua migration
    EXECUTE 'REVOKE INSERT, UPDATE, DELETE ON languages FROM oj_app';

    -- Mặc định cho các bảng tạo ở migration sau này
    EXECUTE 'ALTER DEFAULT PRIVILEGES IN SCHEMA public
             GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO oj_app';
    EXECUTE 'ALTER DEFAULT PRIVILEGES IN SCHEMA public
             GRANT USAGE, SELECT ON SEQUENCES TO oj_app';
END;
$$;
