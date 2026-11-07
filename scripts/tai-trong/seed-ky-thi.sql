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
\if :{?de_ma} \else \set de_ma A-PLUS-B \endif
\if :{?phut} \else \set phut 15 \endif

UPDATE contests SET ends_at = now()
 WHERE lower(slug) LIKE 'tai-trong-ky-thi-%' AND ends_at > now() AND starts_at < now();

SELECT EXISTS (SELECT 1 FROM contest_problems cp
                 JOIN contests c ON c.id = cp.contest_id
                 JOIN problems p ON p.id = cp.problem_id
                WHERE p.code = :'de_ma' AND c.ends_at > now()) AS co_ky_thi_khac \gset
\if :co_ky_thi_khac
    DO $$ BEGIN RAISE EXCEPTION 'Đề nằm trong một kỳ thi khác chưa kết thúc: bài nộp sẽ vào kỳ thi đó, không vào kỳ thi đo.'; END $$;
\endif

SELECT 'tai-trong-ky-thi-' || to_char(now(), 'YYYYMMDD-HH24MISS') AS slug \gset

WITH moi AS (
    INSERT INTO contests (slug, title, format, starts_at, ends_at, penalty_minutes,
                          registration_required, reveal_after_end, created_by)
    SELECT :'slug', 'Load test · vừa xem vừa nộp', 'ICPC',
           now() - interval '1 minute', now() + make_interval(mins => :phut), 20, FALSE, TRUE,
           (SELECT id FROM users WHERE role = 'ADMIN' ORDER BY id LIMIT 1)
    RETURNING id
), de AS (
    INSERT INTO contest_problems (contest_id, problem_id, label, points)
    SELECT moi.id, p.id, 'A', 100 FROM moi, problems p WHERE p.code = :'de_ma'
    RETURNING contest_id
)
SELECT contest_id FROM de;
