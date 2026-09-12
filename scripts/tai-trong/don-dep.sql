-- =============================================================================
-- Xoá sạch dấu vết load test.
--
--   docker exec -i oj-postgres psql -U ojuser -d ojdb -v xac_nhan_db=ojdb < don-dep.sql
--
-- ★ CẦN VAI CÓ QUYỀN UPDATE TRÊN `audit_log`.
-- `audit_log` được thiết kế append-only bằng PHÂN QUYỀN (REVOKE ... FROM
-- oj_app), không bằng trigger. Trên một triển khai đã siết đúng, vai ứng dụng
-- sẽ đứng ở bước gỡ actor_id và cả giao dịch quay đầu — khi đó dùng vai
-- migrator. (Đo trên máy dev ngày 2026-09-04: `ojuser` đang có đủ bảy quyền
-- trên audit_log, tức phần siết ấy CHƯA được áp ở đây.)
--
-- Thứ tự theo chiều khoá ngoại. `submissions` KHÔNG có ON DELETE CASCADE về
-- `users` — cố ý, vì mất một tài khoản không được phép kéo theo lịch sử chấm.
--
-- `source_blobs` giữ lại: khử trùng lặp theo hash, có thể đang được bài nộp
-- thật dùng chung. Vài KB rác không đáng để mạo hiểm xoá nhầm.
-- =============================================================================

-- ★ HÀNG RÀO — gõ lại đúng tên database đang kết nối; tên có "prod" thì từ chối luôn.
-- Nhúng thẳng ở đây chứ không \ir từ file khác: file này được PIPE qua stdin vào psql trong
-- container, nơi không có file nào khác của repo. Seed nhầm vào prod là 1000 tài khoản với mật
-- khẩu có hash công khai trong git; dọn nhầm là xoá mọi tài khoản thật tên "tai-*". Từ
-- 2026-09-11 container có cả ojdb lẫn ojdb_prod, và "$OJ_DB_URL" trong .env trỏ vào ojdb_prod.
\set ON_ERROR_STOP on
\if :{?xac_nhan_db}
\else
    \echo 'Thiếu -v xac_nhan_db=<tên database đang kết nối>.'
    DO $$ BEGIN RAISE EXCEPTION 'thiếu xac_nhan_db'; END $$;
\endif
SELECT current_database() = :'xac_nhan_db' AS khop_ten,
       current_database() ILIKE '%prod%'    AS la_prod \gset
\if :la_prod
    DO $$ BEGIN RAISE EXCEPTION 'Từ chối: database % là production', current_database(); END $$;
\endif
\if :khop_ten
\else
    DO $$ BEGIN RAISE EXCEPTION 'xac_nhan_db không khớp database đang kết nối (%)', current_database(); END $$;
\endif

BEGIN;

CREATE TEMP TABLE nguoi_tai ON COMMIT DROP AS
SELECT id FROM users WHERE handle LIKE 'tai-%';

CREATE TEMP TABLE bai_tai ON COMMIT DROP AS
SELECT id FROM submissions WHERE user_id IN (SELECT id FROM nguoi_tai);

-- `judge_run_subtasks` tự đi theo: khoá ngoại của nó là (submission_id, attempt)
-- REFERENCES judge_runs ... ON DELETE CASCADE. `judge_runs` không có cột `id`,
-- khoá chính là cặp (submission_id, attempt).
DELETE FROM judge_runs  WHERE submission_id IN (SELECT id FROM bai_tai);
DELETE FROM judge_queue WHERE submission_id IN (SELECT id FROM bai_tai);

DELETE FROM contest_problem_standings   WHERE user_id IN (SELECT id FROM nguoi_tai);
DELETE FROM contest_standings_frozen    WHERE user_id IN (SELECT id FROM nguoi_tai);
DELETE FROM contest_standings           WHERE user_id IN (SELECT id FROM nguoi_tai);
DELETE FROM contest_registrations       WHERE user_id IN (SELECT id FROM nguoi_tai);

DELETE FROM submissions WHERE id IN (SELECT id FROM bai_tai);

-- Kỳ thi của kịch bản ky-thi (seed-ky-thi.sql). Đứng SAU submissions: fk_submissions_contest không
-- cascade. Còn bài của người khác (ai đó vào qua tên miền đo) thì chỉ kết thúc kỳ thi, không xoá.
DELETE FROM contests c WHERE lower(c.slug) LIKE 'tai-trong-ky-thi-%'
   AND NOT EXISTS (SELECT 1 FROM submissions s WHERE s.contest_id = c.id);
UPDATE contests SET ends_at = now()
 WHERE lower(slug) LIKE 'tai-trong-ky-thi-%' AND ends_at > now() AND starts_at < now();
DELETE FROM refresh_tokens WHERE user_id IN (SELECT id FROM nguoi_tai);
DELETE FROM login_attempts WHERE handle_tried LIKE 'tai-%';

-- actor_id NULL-able: giữ lại dòng nhật ký, chỉ bỏ liên kết. Nhật ký là bản ghi
-- việc đã xảy ra — xoá nó đi thì lần sau không ai dựng lại được chuyện gì.
UPDATE audit_log SET actor_id = NULL WHERE actor_id IN (SELECT id FROM nguoi_tai);

DELETE FROM users WHERE id IN (SELECT id FROM nguoi_tai);

SELECT (SELECT count(*) FROM users WHERE handle LIKE 'tai-%')       AS con_tai_khoan,
       (SELECT count(*) FROM submissions s JOIN users u ON u.id = s.user_id
         WHERE u.handle LIKE 'tai-%')                                AS con_bai_nop,
       (SELECT count(*) FROM contests WHERE lower(slug) LIKE 'tai-trong-ky-thi-%') AS con_ky_thi;

COMMIT;

\echo ''
\echo '★ File này vừa XOÁ các tài khoản tai-*. Chúng là sân đấu của k6, không phải rác.'
\echo '  Trước khi đo lại PHẢI seed lại, nếu không mọi người ảo sẽ không đăng nhập được'
\echo '  và lượt chạy sẽ ra một bảng toàn dấu tích trên một phép đo rỗng:'
\echo ''
\echo '    docker exec -i oj-postgres psql -U ojuser -d ojdb -v xac_nhan_db=ojdb -v so_nguoi=1000 < seed-nguoi-dung.sql'
\echo ''
