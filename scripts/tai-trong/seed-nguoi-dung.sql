-- =============================================================================
-- Tài khoản ảo cho load test — chạy TRƯỚC k6-tai.js
--
--   docker exec -i oj-postgres psql -U ojuser -d ojdb -v xac_nhan_db=ojdb -v so_nguoi=1000 \
--       < seed-nguoi-dung.sql      (chay.sh tự làm bước này)
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
