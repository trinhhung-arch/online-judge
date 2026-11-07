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
