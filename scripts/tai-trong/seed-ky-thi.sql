-- =============================================================================
-- Kỳ thi cho kịch bản `ky-thi` — chay.sh tự chạy SAU lượt khởi động, in ra id kỳ thi mới.
--
--   docker exec -i oj-postgres psql -U ojuser -d ojdb -q -tA -v xac_nhan_db=ojdb \
--       -v de_ma=A-PLUS-B -v phut=15 < seed-ky-thi.sql
--
-- ★ VÌ SAO PHẢI CÓ KỲ THI RIÊNG
-- Bảng xếp hạng chỉ đổi khi có bài nộp VÀO một kỳ thi đang chạy. Contest 7 của dev-seed không có
-- đề nào (đo 2026-09-11): 1000 kết nối SSE chỉ nhận nhịp tim, không đo được gì của FR-CON-04/P8.
--
-- ★ MỖI LƯỢT MỘT KỲ THI MỚI. Dùng lại kỳ thi cũ thì tài khoản tai-* đã có dòng "giải 1 bài" từ lượt
-- trước, bài AC mới không đổi bảng, và ô "người xem không thấy bài AC của mình" báo hỏng giả.
--
-- ★ ĐỀ NẰM TRONG KỲ THI ĐANG CHẠY THÌ MỌI BÀI NỘP VÀO ĐỀ ĐÓ ĐỀU THUỘC KỲ THI (SubmitSolutionUseCase
-- suy contestId ở máy chủ, lấy kỳ thi id nhỏ nhất). Nên: kỳ thi tự hết hạn sau :phut phút — lượt bị
-- Ctrl+C không để lại một kỳ thi nuốt bài của on-dinh/dot-bien; kỳ thi load test cũ bị kết thúc
-- trước; và kỳ thi KHÁC chưa kết thúc mà chứa cùng đề thì từ chối.
--
-- registration_required = FALSE: chốt BI_KHOA (JdbcContestWindowQuery) cho qua người đã đăng nhập
-- VÀ khách — trang đề công khai vẫn đọc được, nên bước bắt tay của chay.sh vẫn chạy.
-- =============================================================================

-- ★ HÀNG RÀO — cùng khối với seed-nguoi-dung.sql (nhúng thẳng: file đi qua stdin vào container).
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
