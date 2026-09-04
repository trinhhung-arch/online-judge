-- =============================================================================
-- Tài khoản ảo cho load test — chạy TRƯỚC k6-tai.js
--
--   psql "$OJ_DB_URL" -v so_nguoi=1000 -f seed-nguoi-dung.sql
--
-- ★ VÌ SAO SEED BẰNG SQL CHỨ KHÔNG ĐĂNG KÝ QUA API
--
-- `bcrypt-cost: 12` là ~250ms mỗi lần băm. Đăng ký 1000 tài khoản qua API là
-- ~4 phút CPU chỉ để dựng sân, và nó nhuộm luôn phép đo: máy chủ đang gánh
-- bcrypt trong lúc ta tưởng mình đang đo đường nộp bài.
--
-- Băm dưới đây là của "matkhau-dev-123", chép từ R__seed_du_lieu_dev.sql. Nó
-- KHÔNG phải mật khẩu thật của ai — và cũng vì thế, đừng chạy file này lên một
-- database có người dùng thật.
--
-- Handle mang tiền tố `tai-` để `don-dep.sql` xoá lại được chính xác, và để
-- không ai nhầm chúng với tài khoản thật.
-- =============================================================================

\if :{?so_nguoi} \else \set so_nguoi 1000 \endif

INSERT INTO users (handle, email, password_hash, display_name, role, status)
SELECT 'tai-' || i,
       'tai-' || i || '@loadtest.invalid',
       '$2a$12$nbmWQ37swiou9I6Nm3N1YuQcTPBYnK/nSbXxt2M0FZc9GkUuldEoS',
       'Tải ' || i,
       'USER',
       'ACTIVE'
  FROM generate_series(1, :so_nguoi) AS i
 WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.handle = 'tai-' || i);

SELECT count(*) AS tai_khoan_tai_trong FROM users WHERE handle LIKE 'tai-%';
