-- =============================================================================
-- Xoá sạch dấu vết load test.
--
--   psql "postgres://<migrator>@localhost:5432/ojdb" -f don-dep.sql
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
DELETE FROM refresh_tokens WHERE user_id IN (SELECT id FROM nguoi_tai);
DELETE FROM login_attempts WHERE handle_tried LIKE 'tai-%';

-- actor_id NULL-able: giữ lại dòng nhật ký, chỉ bỏ liên kết. Nhật ký là bản ghi
-- việc đã xảy ra — xoá nó đi thì lần sau không ai dựng lại được chuyện gì.
UPDATE audit_log SET actor_id = NULL WHERE actor_id IN (SELECT id FROM nguoi_tai);

DELETE FROM users WHERE id IN (SELECT id FROM nguoi_tai);

SELECT (SELECT count(*) FROM users WHERE handle LIKE 'tai-%')       AS con_tai_khoan,
       (SELECT count(*) FROM submissions s JOIN users u ON u.id = s.user_id
         WHERE u.handle LIKE 'tai-%')                                AS con_bai_nop;

COMMIT;
