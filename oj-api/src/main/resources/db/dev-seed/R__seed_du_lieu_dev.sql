-- =============================================================================
-- DỮ LIỆU DEV — CHỈ chạy khi profile `dev` đang bật.
--
-- Thư mục này KHÔNG nằm trong `spring.flyway.locations` mặc định. Nó chỉ được thêm
-- vào bởi `application-dev.yml`. Chạy nhầm nó trên host là tạo một tài khoản không
-- mật khẩu tên 'dev' trên hệ thống thật.
--
-- ⚠️ VÌ SAO FILE NÀY PHẢI TỒN TẠI:
-- `FixedDevUserProvider` (hiện thực M1 của CurrentUserProvider) trả về users.id = 1
-- cho mọi request, còn `submissions.user_id` có REFERENCES users(id). Không có file
-- này thì lần POST /api/v1/submissions ĐẦU TIÊN vỡ khoá ngoại, và DoD của M1
-- ("nộp bài trả 202 < 300ms") không thể đạt được. Tương tự với đề A-PLUS-B mà
-- build-order Bước M1-4 nói là "seed bằng SQL".
--
-- Repeatable (R__): Flyway chạy lại mỗi khi checksum đổi. Toàn bộ file idempotent.
-- =============================================================================

-- ----- Người dùng -----------------------------------------------------------
-- OVERRIDING SYSTEM VALUE: cột id là GENERATED ALWAYS, nhưng ở đây ta CẦN đúng
-- id = 1 vì FixedDevUserProvider gán cứng con số đó.
INSERT INTO users (id, handle, display_name, role) OVERRIDING SYSTEM VALUE VALUES
    (1, 'dev',    'Người dùng dev', 'USER'),
    (2, 'setter', 'Người ra đề',    'SETTER')
ON CONFLICT (id) DO NOTHING;

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
