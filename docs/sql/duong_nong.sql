-- =============================================================================
-- Các truy vấn trên đường nóng — chép nguyên văn vào repository, đừng viết lại.
-- Tất cả dùng named parameter của JdbcClient (bất biến #5: không nối chuỗi SQL).
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
       lease_until     = now() + (:leaseSeconds || ' seconds')::interval,  -- 120s
       claimed_by_host = :hostId,
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
RETURNING submission_id, attempt;

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
--    MỘT câu, nguyên tử, không race. 0 dòng trả về = hết quota.
--    Đừng làm bằng SELECT rồi IF rồi UPDATE — hai tab trình duyệt là đủ để lách.
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
-- ─────────────────────────────────────────────────────────────────────────────
SELECT s.id, s.verdict, s.failed_test_ordinal, p.feedback_level
  FROM submissions s
  JOIN problems p ON p.id = s.problem_id
 WHERE s.id = :submissionId
   AND (s.user_id = :requesterId OR :requesterRole = 'ADMIN');

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
--     Chặn khoảng id để `ix_submissions_problem_recent` cắt gần hết bảng, nhờ
--     vậy không cần index riêng cho contest_id trên bảng nóng.
-- ─────────────────────────────────────────────────────────────────────────────
SELECT s.user_id, s.id, s.verdict, s.score, s.created_at
  FROM submissions s
 WHERE s.problem_id = :problemId
   AND s.id BETWEEN :minSubmissionId AND :maxSubmissionId
   AND s.contest_id = :contestId
   AND s.status = 'DONE'
 ORDER BY s.id;

-- ─────────────────────────────────────────────────────────────────────────────
-- 12. TRANG TRẠNG THÁI CÔNG KHAI — FR-ADM-05. Đếm trên hàng đợi vài trăm dòng,
--     không phải COUNT(*) trên `submissions`.
-- ─────────────────────────────────────────────────────────────────────────────
SELECT count(*) FILTER (WHERE claimed_at IS NULL)     AS queued,
       count(*) FILTER (WHERE claimed_at IS NOT NULL) AS judging,
       COALESCE(EXTRACT(EPOCH FROM now() - min(enqueued_at)) * 1000, 0)::int AS oldest_wait_ms
  FROM judge_queue;
