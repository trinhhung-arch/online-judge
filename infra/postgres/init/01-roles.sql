-- Quyết định D (postgres-design.md mục 9): HAI role, không phải một.
-- Chạy MỘT LẦN duy nhất, lúc volume postgres còn TRỐNG. Nếu volume đã tồn tại từ
-- trước thì Postgres bỏ qua toàn bộ /docker-entrypoint-initdb.d và hai role không
-- được tạo — triệu chứng: `SELECT rolname FROM pg_roles WHERE rolname LIKE 'oj%'`
-- chỉ trả về `ojuser`.
--
-- Khôi phục mà KHÔNG mất dữ liệu (file đã được mount sẵn vào container):
--     docker compose exec -T postgres psql -U ojuser -d ojdb \
--         -f /docker-entrypoint-initdb.d/01-roles.sql
--
-- Đừng dùng `docker compose down -v` chỉ để chạy lại file này: nó xoá sạch mọi
-- volume, kể cả bài nộp trong DB dev.
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
