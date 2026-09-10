-- =============================================================================
-- Các truy vấn trên đường nóng — chép nguyên văn vào repository, đừng viết lại.
-- Tất cả dùng named parameter của JdbcClient (bất biến #5: không nối chuỗi SQL).
--
-- ★ ĐÃ ĐỐI CHIẾU VỚI CODE ĐANG CHẠY ngày 2026-09-08. Chỗ nào lệch thì file này
--   đã được sửa theo CODE, không phải ngược lại — vì code là thứ đã chạy qua test.
--   Bản trước của file này có hai chỗ nguy hiểm, ghi ra đây để không ai chép lại
--   từ một bản cũ nào đó còn sót:
--
--     * Câu 9 thiếu điều kiện `hidden_at` -> chủ bài nộp vẫn xem được bài mà
--       ADMIN đã ẩn (FR-SUB-09 hỏng một nửa).
--     * Câu 11 không có LIMIT và dùng hai tham số không tồn tại trong mã nguồn
--       -> nạp trọn một kỳ thi vào bộ nhớ.
--
--   Cách kiểm lại bất cứ lúc nào: mỗi câu dưới đây ghi tên hằng số và file Java
--   tương ứng. `grep -n "<TEN_HANG>" -r oj-api/src/main` là ra.
-- =============================================================================

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. NHẬN BÀI NỘP — POST /api/v1/submissions, ngân sách 50ms cho phần DB.
--    Ba câu, MỘT transaction, rồi COMMIT. Publish RabbitMQ nằm NGOÀI transaction
--    (oj-api/CLAUDE.md mục 1). Publish hỏng cũng không sao: hàng judge_queue đã
--    có trong DB, reaper sẽ nhặt.
-- ─────────────────────────────────────────────────────────────────────────────

-- 1a. Khử trùng lặp source (cùng lúc là khoá cache biên dịch của worker)
INSERT INTO source_blobs (sha256, content, byte_size)
VALUES (:sha256, :content, :byteSize)
ON CONFLICT (sha256) DO NOTHING;

-- 1b. Ghi bài nộp
INSERT INTO submissions (user_id, problem_id, contest_id, language_id,
                         source_sha256, source_bytes, testdata_version)
VALUES (:userId, :problemId, :contestId, :languageId,
        :sha256, :byteSize, :testdataVersion)
RETURNING id, created_at;

-- 1c. Đưa vào hàng đợi bền
INSERT INTO judge_queue (submission_id, priority, attempt)
VALUES (:submissionId, 0, 0)
ON CONFLICT (submission_id) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. WORKER CLAIM — POST /internal/judge/claim.
--    FOR UPDATE SKIP LOCKED: N worker chạy song song, không worker nào chờ nhau
--    và không job nào bị giao hai lần trong cùng thời điểm.
--    `attempt` tăng ở ĐÂY. Đó là điều làm cho kết quả của một worker đã bị reaper
--    thu hồi tự động bị từ chối ở bước 3 — không cần cơ chế nào khác.
--
--    Code: JdbcJudgeQueueRepository.CLAIM
--
--    ★ HAI CHI TIẾT KHÔNG ĐƯỢC VIẾT KHÁC ĐI
--    1. `claimed_by_host` phân giải bằng SUB-SELECT theo TÊN máy, không nhận
--       `:hostId` từ worker. Worker không được biết `judge_hosts.id` tồn tại —
--       nó chỉ biết bốn đường dẫn trong `JudgeEndpoints` (bất biến #3). Nhận id
--       từ worker là bắt worker biết lược đồ CSDL của API.
--       Phụ thêm: máy chưa đăng ký thì cột nhận NULL và bài vẫn chấm được ngay
--       (S2 — "worker mới join: 0 thao tác phía API").
--    2. `make_interval(secs => :leaseSeconds)` chứ KHÔNG phải
--       `(:leaseSeconds || ' seconds')::interval`.
--       Cả hai đều PREPARE được — đo trên Postgres 16 ngày 2026-09-08 — nhưng
--       kiểu tham số suy ra KHÁC NHAU:
--           bản cũ  -> parameter_types = {text}
--           bản mới  -> parameter_types = {double precision}
--       `leaseSeconds` là một `int` trong Java (`AppProperties.leaseSeconds()`).
--       Bản cũ buộc nó đi vòng qua text: JDBC phải bind một chuỗi, và giá trị
--       lease — thứ quyết định sau bao lâu reaper cướp bài khỏi worker — được
--       ghép bằng nối chuỗi thay vì bằng số học. Bản mới nhận thẳng con số.
--       Kiểm lại: PREPARE cả hai rồi đọc `pg_prepared_statements.parameter_types`.
-- ─────────────────────────────────────────────────────────────────────────────
WITH picked AS (
    SELECT submission_id
      FROM judge_queue
     WHERE claimed_at IS NULL
     ORDER BY priority, enqueued_at, submission_id
     LIMIT 1
       FOR UPDATE SKIP LOCKED
)
UPDATE judge_queue q
   SET claimed_at      = now(),
       lease_until     = now() + make_interval(secs => :leaseSeconds),      -- 120s
       claimed_by_host = (SELECT id FROM judge_hosts WHERE name = :hostName),
       attempt         = q.attempt + 1
  FROM picked p
 WHERE q.submission_id = p.submission_id
RETURNING q.submission_id, q.attempt;

-- Sau đó, cùng transaction: đánh dấu trạng thái để UI thấy (HOT update — không
-- cột nào được index bị đổi).
UPDATE submissions
   SET status = 'JUDGING', attempt = :attempt
 WHERE id = :submissionId;

-- ─────────────────────────────────────────────────────────────────────────────
-- 3. GHI KẾT QUẢ — POST /internal/judge/result. MỘT transaction.
--    Câu 3a LÀ khoá lạc quan (bất biến #7). RabbitMQ là at-least-once, nên câu
--    này CHẮC CHẮN sẽ có lúc trả 0 dòng — đó là lúc nó làm đúng việc của nó.
-- ─────────────────────────────────────────────────────────────────────────────

-- 3a. Khoá lạc quan: xoá hàng khỏi hàng đợi đúng attempt đang giữ.
--     0 dòng  -> kết quả trùng hoặc kết quả của attempt đã bị thu hồi -> BỎ QUA
--                toàn bộ transaction, không ghi gì, không báo lỗi.
DELETE FROM judge_queue
 WHERE submission_id = :submissionId
   AND attempt       = :attempt
RETURNING submission_id;

-- 3b. Lịch sử chấm — không bao giờ ghi đè verdict cũ (rejudge tạo attempt mới)
INSERT INTO judge_runs (submission_id, attempt, host_id, host_factor, language_id,
                        testdata_version, verdict, score, max_score,
                        failed_test_ordinal, tests_run, time_ms, memory_kb,
                        compile_log, isolate_status, trace_id, started_at, finished_at)
VALUES (:submissionId, :attempt, :hostId, :hostFactor, :languageId,
        :testdataVersion, :verdict, :score, :maxScore,
        :failedTestOrdinal, :testsRun, :timeMs, :memoryKb,
        :compileLog, :isolateStatus, :traceId, :startedAt, now())
ON CONFLICT (submission_id, attempt) DO NOTHING;

-- 3c. Ảnh chụp hiện tại trên bảng nóng (HOT update)
UPDATE submissions
   SET status              = 'DONE',
       attempt             = :attempt,
       verdict             = :verdict,
       score               = :score,
       max_score           = :maxScore,
       failed_test_ordinal = :failedTestOrdinal,
       time_ms             = :timeMs,
       memory_kb           = :memoryKb,
       testdata_version    = :testdataVersion,
       judged_at           = now()
 WHERE id = :submissionId;

-- ─────────────────────────────────────────────────────────────────────────────
-- 3'. TRƯỜNG HỢP IE — FR-SUB-12: tự chấm lại tối đa 2 lần.
--     Thay vì xoá hàng đợi, TRẢ nó về trạng thái chờ. Nếu đã hết lượt thì mới
--     chạy nhánh 3a/3b/3c bình thường với verdict = 'IE'.
-- ─────────────────────────────────────────────────────────────────────────────
UPDATE judge_queue
   SET claimed_at      = NULL,
       lease_until     = NULL,
       claimed_by_host = NULL,
       ie_retry_count  = ie_retry_count + 1,
       enqueued_at     = now()
 WHERE submission_id = :submissionId
   AND attempt       = :attempt
   AND ie_retry_count < :maxIeRetries          -- 2
RETURNING submission_id, ie_retry_count;

-- ─────────────────────────────────────────────────────────────────────────────
-- 4. REAPER — job nền, chạy mỗi 15 giây (nfrplan 5.1: một cơ chế, năm loại sự cố).
--    Không tăng attempt ở đây: lần claim kế tiếp sẽ tăng, và chính điều đó vô
--    hiệu hoá kết quả trả về muộn của worker đã chết.
-- ─────────────────────────────────────────────────────────────────────────────
UPDATE judge_queue
   SET claimed_at      = NULL,
       lease_until     = NULL,
       claimed_by_host = NULL
 WHERE claimed_at IS NOT NULL
   AND lease_until  < now()
RETURNING submission_id;

-- Đưa các bài đó về QUEUED trên bảng nóng
UPDATE submissions SET status = 'QUEUED' WHERE id = ANY(:submissionIds);

-- ─────────────────────────────────────────────────────────────────────────────
-- 5. DỰNG LẠI HÀNG ĐỢI SAU KHI MẤT RABBITMQ (nfrplan 5.2, kịch bản "Kill RabbitMQ")
--    Vài trăm dòng, index scan. Không quét `submissions`.
-- ─────────────────────────────────────────────────────────────────────────────
SELECT submission_id
  FROM judge_queue
 WHERE claimed_at IS NULL
 ORDER BY priority, enqueued_at, submission_id;

-- ─────────────────────────────────────────────────────────────────────────────
-- 6. LỊCH SỬ BÀI NỘP CỦA MÌNH — FR-SUB-07, cursor-based, tối đa 50.
--    Không COUNT(*), không OFFSET (bất biến #8).
--    :cursorId = NULL cho trang đầu.
-- ─────────────────────────────────────────────────────────────────────────────
SELECT s.id, s.problem_id, p.code, p.title, s.language_id,
       s.status, s.verdict, s.score, s.time_ms, s.memory_kb, s.created_at
  FROM submissions s
  JOIN problems p ON p.id = s.problem_id
 WHERE s.user_id = :userId
   AND (:cursorId::BIGINT IS NULL OR s.id < :cursorId)
   AND (:problemId::BIGINT IS NULL OR s.problem_id = :problemId)
   AND (:verdict::TEXT    IS NULL OR s.verdict    = :verdict)
   AND (:languageId::SMALLINT IS NULL OR s.language_id = :languageId)
   AND s.hidden_at IS NULL
 ORDER BY s.id DESC
 LIMIT :pageSize;                              -- mặc định 20, trần 50

-- ─────────────────────────────────────────────────────────────────────────────
-- 7. RATE LIMIT 1 BÀI/10s — FR-SUB-08.
--    Đường chính là Redis. Đây là đường dự phòng khi Redis chết: index-only scan
--    trên ix_submissions_user_recent (created_at nằm trong INCLUDE).
-- ─────────────────────────────────────────────────────────────────────────────
SELECT created_at
  FROM submissions
 WHERE user_id = :userId
 ORDER BY id DESC
 LIMIT 1;

-- ─────────────────────────────────────────────────────────────────────────────
-- 8. QUOTA AI 5 LƯỢT/NGÀY — FR-AI-03.
--
--    ⚠️ BẢNG `ai_quota_usage` CHƯA TỒN TẠI. Không migration nào tạo nó, và
--       package `dev.oj.ai` cũng chưa có. Câu này là THIẾT KẾ để dành cho tuần
--       14-15, không phải mã đang chạy — đừng grep tìm nó trong repository rồi
--       tưởng mình bỏ sót. Xem `docs/frplan.md` mục 2.5.
--
--    MỘT câu, nguyên tử, không race. 0 dòng trả về = hết quota.
--    Đừng làm bằng SELECT rồi IF rồi UPDATE — hai tab trình duyệt là đủ để lách.
--
--    ⚠️ CÂU DUY NHẤT TRONG FILE NÀY CHƯA CHẠY ĐƯỢC. `ai_quota_usage` chưa có
--    migration nào tạo, và module `ai` chưa tồn tại (CLAUDE.md mục 3 — tuần
--    14-15). Header của file nói "chép nguyên văn vào repository"; với riêng
--    câu này thì chép xong sẽ nhận `relation "ai_quota_usage" does not exist`.
--    Giữ lại vì hình dạng câu lệnh mới là thứ đáng giá: nó phải là MỘT câu.
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ai_quota_usage (user_id, usage_date, used_count)
VALUES (:userId, CURRENT_DATE, 1)
ON CONFLICT (user_id, usage_date) DO UPDATE
   SET used_count = ai_quota_usage.used_count + 1
 WHERE ai_quota_usage.used_count < :dailyLimit  -- 5
RETURNING used_count;

-- ─────────────────────────────────────────────────────────────────────────────
-- 9. CHỐNG IDOR — điều kiện chủ sở hữu nằm TRONG câu query, không phải câu if
--    ở service (oj-api/CLAUDE.md mục 2).
--
--    Code: JdbcSubmissionRepository.FIND_FOR_REQUESTER
--                              và .FIND_DETAIL_FOR_REQUESTER
--
--    ★ HAI ĐIỀU KIỆN, KHÔNG PHẢI MỘT. Bản trước của file này chỉ có dòng
--      `user_id`, và đó là lỗi: bài nộp bị ADMIN ẩn (FR-SUB-09) vẫn hiện ra với
--      chính người nộp. "Ẩn" mà chủ bài vẫn xem được thì không phải ẩn — ADMIN
--      ẩn một bài thường là vì nội dung của nó có vấn đề, tức là đúng lúc cần
--      chặn nhất thì hàng rào không có.
--
--      Để ý câu 6 ngay trên đã có `hidden_at IS NULL`. Hai câu cùng đọc một bảng
--      thì phải cùng một luật hiển thị; lệch nhau là có một đường vòng.
-- ─────────────────────────────────────────────────────────────────────────────
SELECT s.id, s.verdict, s.failed_test_ordinal, p.feedback_level
  FROM submissions s
  JOIN problems p ON p.id = s.problem_id
 WHERE s.id = :submissionId
   AND (s.user_id   = :requesterId OR :requesterRole = 'ADMIN')
   AND (s.hidden_at IS NULL        OR :requesterRole = 'ADMIN');

-- ─────────────────────────────────────────────────────────────────────────────
-- 10. TESTCASE SAMPLE ĐƯỢC PHÉP HIỂN THỊ — FR-PROB-04.
--     Không cần lọc is_sample: bảng sample_testcase_contents theo thiết kế
--     KHÔNG THỂ chứa test ẩn (ràng buộc FK tổng hợp ở V2).
-- ─────────────────────────────────────────────────────────────────────────────
SELECT t.ordinal, c.input_text, c.output_text, c.explanation
  FROM sample_testcase_contents c
  JOIN testcases t ON t.id = c.testcase_id
 WHERE t.problem_id = :problemId
   AND t.testdata_version = :testdataVersion
 ORDER BY t.ordinal;

-- ─────────────────────────────────────────────────────────────────────────────
-- 11. DỰNG LẠI BẢNG XẾP HẠNG — FR-CON-08, job nền theo từng đề.
--
--     Code: JdbcJudgingQueries.CUA_DE
--
--     ★ THỨ TỰ ĐIỀU KIỆN LÀ HIỆU NĂNG, KHÔNG PHẢI THẨM MỸ. `problem_id` đứng
--       trước để `ix_submissions_problem_recent` cắt gần hết bảng; `contest_id`
--       chỉ là bộ lọc thêm trên phần đã cắt. Viết ngược lại thì Postgres phải
--       quét theo `contest_id` — cột CỐ Ý không có index (ngân sách index của
--       `submissions` là 3-4, đang dùng 2, nfrplan 2.3).
--
--     ★ PHÂN TRANG BẰNG CON TRỎ, KHÔNG PHẢI KHOẢNG id. Bản trước của file này
--       viết `id BETWEEN :minSubmissionId AND :maxSubmissionId` và KHÔNG có
--       LIMIT. Hai tham số ấy chưa bao giờ tồn tại trong mã nguồn, và câu không
--       LIMIT nạp trọn một kỳ thi vào bộ nhớ — đúng thứ bất biến #8 cấm. Job gọi
--       lại nhiều lần, mỗi lần truyền `:sau` = id lớn nhất của lô trước.
-- ─────────────────────────────────────────────────────────────────────────────
SELECT id, user_id, problem_id, verdict, COALESCE(score, 0) AS score, created_at
  FROM submissions
 WHERE problem_id = :problemId
   AND contest_id = :contestId
   AND status     = 'DONE'
   AND id > :sau                        -- 0 cho lô đầu tiên
 ORDER BY id
 LIMIT :gioiHan;

-- ─────────────────────────────────────────────────────────────────────────────
-- 12. TRANG TRẠNG THÁI CÔNG KHAI — FR-ADM-05. Đếm trên hàng đợi vài trăm dòng,
--     không phải COUNT(*) trên `submissions`.
--
--     Code: JdbcJudgeQueueRepository.QUEUE_DEPTH
--
--     ★ TRẢ VỀ MỐC THỜI GIAN, KHÔNG TRẢ VỀ KHOẢNG CÁCH. Bản trước tính
--       `oldest_wait_ms` ngay trong SQL bằng `now()`. Làm vậy thì thời gian của
--       câu trả lời do đồng hồ CSDL quyết định, và tầng trên không test được nếu
--       không dựng một Postgres thật. Trả `min(enqueued_at)` rồi để ứng dụng trừ
--       bằng `Clock` của nó: cùng một con số, mà test tiêm được đồng hồ giả.
-- ─────────────────────────────────────────────────────────────────────────────
SELECT count(*) FILTER (WHERE claimed_at IS NULL)     AS queued,
       count(*) FILTER (WHERE claimed_at IS NOT NULL) AS judging,
       min(enqueued_at)                               AS oldest_enqueued_at
  FROM judge_queue;
