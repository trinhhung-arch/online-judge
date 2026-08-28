-- Quyết định D (postgres-design.md mục 9): HAI role, không phải một.
-- Chạy MỘT LẦN duy nhất, lúc volume postgres còn trống. Đổi file này sau đó thì
-- phải `docker compose down -v` mới có tác dụng — nói trước để khỏi mất buổi debug.
--
--   oj_migrator  sở hữu schema, chạy Flyway, có DDL
--   oj_app       chỉ DML; V9 sẽ REVOKE DELETE/TRUNCATE trên submissions và
--                REVOKE UPDATE/DELETE trên audit_log + judge_runs
--
-- 15 phút cấu hình đổi lấy: một lỗ SQL injection lọt lưới cũng không DROP TABLE được,
-- và "append-only" trở thành một quyền hệ thống chứ không phải một lời hứa.
--
-- ⚠️ Hai role đã tồn tại từ đây, NHƯNG ứng dụng vẫn chạy bằng POSTGRES_USER cho tới
-- khi V9 (M6) cấp quyền cho oj_app. Đổi sớm hơn là app không đọc được bảng nào.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'oj_migrator') THEN
        CREATE ROLE oj_migrator LOGIN PASSWORD 'ojmigrator';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'oj_app') THEN
        CREATE ROLE oj_app LOGIN PASSWORD 'ojapp';
    END IF;
END
$$;

COMMENT ON ROLE oj_migrator IS 'Flyway. Sở hữu schema, có DDL.';
COMMENT ON ROLE oj_app      IS 'oj-api lúc chạy. DML, không DDL. Xem V9.';
