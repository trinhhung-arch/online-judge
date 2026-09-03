-- =============================================================================
-- V8 — Phân quyền ở tầng database
-- Mốc: M6 (cùng lúc siết vận hành). Quyết định D của build-order.md PHẦN 0.
--
-- ⚠️ ĐÁNH SỐ: build-order.md Bước 6.1 gọi file này là "V9", và bản nháp trong
--    docs/sql/migration-cho-moc-sau/ cũng mang số đó. Nó là V8 ở đây vì hạ tầng
--    job được kéo lên tuần 7 (phương án (a)) và đã chiếm V6, đẩy contests xuống
--    V7. Bản nháp ai_review đổi thành V9 theo. Số hiệu là thứ tự áp dụng, không
--    phải một cái tên — và một KHOẢNG TRỐNG trong dãy Flyway (V8 thiếu, V9 có)
--    làm `validate` đỏ ở lần migration sau, nên không được để dành số.
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

-- =============================================================================
-- ★ Hệ quả của khối trên mà bản nháp chưa lường: hàm tạo partition audit_log
--   không còn chạy được bằng `oj_app`.
--
-- `create_audit_log_partition` (V5) là DDL — nó `CREATE TABLE ... PARTITION OF`.
-- Với SECURITY INVOKER (mặc định) nó chạy dưới quyền người gọi, và người gọi từ
-- M6 là `oj_app`, role vừa bị lấy hết quyền DDL ở trên. Job hàng tháng của
-- Bước 6.5 sẽ hỏng với `permission denied for schema public` — vào một ngày
-- cuối tháng, tức là đúng lúc không ai nhìn.
--
-- Đây là loại lỗi mà việc siết quyền LUÔN sinh ra: quyền bị lấy đi ở một chỗ,
-- triệu chứng hiện ra ở một chỗ khác, muộn hơn. Cách chữa đúng không phải là
-- trả lại quyền DDL cho `oj_app` — làm thế là bỏ toàn bộ giá trị của V8 — mà là
-- cho riêng hàm này chạy dưới quyền chủ sở hữu schema.
--
-- Bề mặt mở thêm đúng bằng một hàm nhận một tham số DATE và chỉ tạo được bảng
-- tên `audit_log_YYYY_MM`; không có đường nào truyền chuỗi vào `format(%I)`.
-- `SET search_path` là phần bắt buộc của mọi SECURITY DEFINER: thiếu nó thì
-- người gọi đặt được một schema riêng lên đầu đường tìm và cướp quyền chủ sở hữu.
-- =============================================================================
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
END;
$$;

COMMENT ON FUNCTION create_audit_log_partition(DATE) IS
    'SECURITY DEFINER: oj_app không có quyền DDL từ V8, nhưng job hàng tháng (Bước 6.5) phải tạo được partition.';
