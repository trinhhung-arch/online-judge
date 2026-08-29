-- =============================================================================
-- DỮ LIỆU DEV — CHỈ chạy khi profile `dev` đang bật.
--
-- Thư mục này KHÔNG nằm trong `spring.flyway.locations` mặc định. Nó chỉ được thêm
-- vào bởi `application-dev.yml`. Chạy nhầm nó trên host là tạo một tài khoản không
-- mật khẩu tên 'dev' trên hệ thống thật.
--
-- ⚠️ VÌ SAO FILE NÀY PHẢI TỒN TẠI:
-- `submissions.user_id` có REFERENCES users(id) và `problems.owner_id` cũng vậy.
-- Không có file này thì lần POST /api/v1/submissions ĐẦU TIÊN vỡ khoá ngoại, và
-- DoD của M1 ("nộp bài trả 202 < 300ms") không thể đạt được. Tương tự với đề
-- A-PLUS-B mà build-order Bước M1-4 nói là "seed bằng SQL".
--
-- Ở M1 file này phục vụ `FixedDevUserProvider`, thứ trả về users.id = 1 cho mọi
-- request. Cửa hậu đó đã bị XOÁ ở Bước 4.5; ba tài khoản dưới đây giờ là tài
-- khoản thật, có mật khẩu thật, đăng nhập qua /api/v1/auth/login như mọi người.
--
-- Repeatable (R__): Flyway chạy lại mỗi khi checksum đổi. Toàn bộ file idempotent.
-- =============================================================================

-- ----- Người dùng -----------------------------------------------------------
-- OVERRIDING SYSTEM VALUE: cột id là GENERATED ALWAYS, nhưng ở đây ta CẦN đúng
-- những id cố định vì test và dữ liệu seed bên dưới tham chiếu tới chúng.
--
-- ⚠️ MẬT KHẨU CHUNG CHO CẢ BA TÀI KHOẢN: matkhau-dev-123
--
-- Băm dưới đây là BCrypt cost 12 thật, không phải chuỗi giả — Bước 4.4 đã thay
-- FixedDevUserProvider bằng JwtCurrentUserProvider, nên từ M4 muốn gọi API là
-- phải đăng nhập thật, kể cả trên máy dev.
--
-- Ba dòng này chỉ chạy khi profile `dev` bật. Một mật khẩu viết trong mã nguồn
-- công khai mà lọt lên host là mất trắng, và đó là lý do thư mục này KHÔNG nằm
-- trong spring.flyway.locations mặc định.
--
-- DO UPDATE chứ không DO NOTHING: R__ chạy lại mỗi khi checksum đổi, và một
-- máy dev đã seed từ M1 sẽ có ba tài khoản KHÔNG có mật khẩu — tức là không
-- đăng nhập được, mà cũng không hiểu vì sao.
INSERT INTO users (id, handle, email, display_name, role, password_hash)
OVERRIDING SYSTEM VALUE VALUES
    (1, 'dev',    'dev@oj.test',    'Người dùng dev', 'USER',
     '$2a$12$nbmWQ37swiou9I6Nm3N1YuQcTPBYnK/nSbXxt2M0FZc9GkUuldEoS'),
    (2, 'setter', 'setter@oj.test', 'Người ra đề',    'SETTER',
     '$2a$12$nbmWQ37swiou9I6Nm3N1YuQcTPBYnK/nSbXxt2M0FZc9GkUuldEoS'),
    (3, 'admin',  'admin@oj.test',  'Quản trị viên',  'ADMIN',
     '$2a$12$nbmWQ37swiou9I6Nm3N1YuQcTPBYnK/nSbXxt2M0FZc9GkUuldEoS')
ON CONFLICT (id) DO UPDATE
   SET email = EXCLUDED.email,
       display_name = EXCLUDED.display_name,
       role = EXCLUDED.role,
       password_hash = EXCLUDED.password_hash;

-- Đẩy sequence lên quá các id vừa chèn tay, nếu không thì lần đăng ký thật đầu tiên
-- (M4) sẽ xin id = 1 và đâm vào khoá chính.
SELECT setval(pg_get_serial_sequence('users', 'id'),
              GREATEST((SELECT max(id) FROM users), 1));

-- ----- Đề A-PLUS-B (build-order Bước M1-4) ----------------------------------
INSERT INTO problems (id, code, title, statement_md, statement_hash,
                      time_limit_ms, memory_limit_kb, owner_id,
                      status, published_at, current_testdata_version)
OVERRIDING SYSTEM VALUE VALUES
    (1, 'A-PLUS-B', 'A + B',
     E'Cho hai số nguyên `a` và `b`. In ra `a + b`.\n\n**Đầu vào:** một dòng chứa `a b`.\n\n**Đầu ra:** một số nguyên.',
     encode(sha256('A-PLUS-B v1'::bytea), 'hex'),
     1000, 262144, 2,
     'PUBLISHED', now(), 1)
ON CONFLICT (id) DO NOTHING;
SELECT setval(pg_get_serial_sequence('problems', 'id'),
              GREATEST((SELECT max(id) FROM problems), 1));

-- ----- Testdata version 1 ---------------------------------------------------
-- Chỉ METADATA + sha256. Nội dung test ẩn không nằm trong Postgres ở bất kỳ đâu
-- (bất biến #1) — nó sống trên MinIO, và worker tải theo hash.
INSERT INTO testdata_versions (problem_id, version, manifest_sha256, test_count, total_bytes, created_by)
VALUES (1, 1, encode(sha256('A-PLUS-B manifest v1'::bytea), 'hex'), 3, 64, 2)
ON CONFLICT (problem_id, version) DO NOTHING;

INSERT INTO testcases (problem_id, testdata_version, ordinal, is_sample,
                       input_sha256, output_sha256, input_bytes, output_bytes)
VALUES
    (1, 1, 1, TRUE,  encode(sha256('1 2'::bytea), 'hex'), encode(sha256('3'::bytea), 'hex'), 4, 2),
    (1, 1, 2, FALSE, encode(sha256('10 20'::bytea), 'hex'), encode(sha256('30'::bytea), 'hex'), 6, 3),
    (1, 1, 3, FALSE, encode(sha256('-5 5'::bytea), 'hex'), encode(sha256('0'::bytea), 'hex'), 5, 2)
ON CONFLICT (problem_id, testdata_version, ordinal) DO NOTHING;

-- Nội dung CHỈ của test sample. Bảng này theo ràng buộc khoá ngoại tổng hợp ở V2
-- KHÔNG THỂ chứa một testcase ẩn — thử chèn ordinal 2 vào đây sẽ ra lỗi khoá ngoại.
INSERT INTO sample_testcase_contents (testcase_id, input_text, output_text, explanation)
SELECT t.id, '1 2', '3', 'Một cộng hai bằng ba.'
  FROM testcases t
 WHERE t.problem_id = 1 AND t.testdata_version = 1 AND t.ordinal = 1
ON CONFLICT (testcase_id) DO NOTHING;
