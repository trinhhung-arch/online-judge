-- =============================================================================
-- V9 — Một job đang sống cho mỗi (loại, thực thể), thay vì cho mỗi loại
-- Mốc: M6, Bước 6.14 (FR-PROB-10).
--
-- ★ Đây là sửa một lỗi CÓ SẴN TỪ M4, không phải một tính năng mới.
--
-- V6 tạo `ux_jobs_one_active_per_type ON jobs (type)`, với lý do đúng: chặn thảm
-- hoạ "ba job rejudge hàng loạt chạy song song" mà một cú double click là đủ để
-- tạo ra. Nhưng nó chặn rộng hơn ý định:
--
--   * HAI SETTER không nạp testdata cùng lúc được. Người thứ hai nhận
--     "đang có job cùng loại" và không hiểu vì sao — họ đang làm việc trên một
--     đề hoàn toàn khác. Đây là lỗi đã có từ Bước 4.10 và chưa ai gặp vì mới
--     có một người dùng thật.
--   * FR-PROB-10 buộc "sửa testdata -> BẮT BUỘC tạo job rejudge". Với index cũ,
--     nếu đang có một job rejudge cho đề khác thì lệnh bắt buộc ấy KHÔNG thực
--     hiện được, và hệ thống phải chọn giữa hai điều xấu: hỏng việc nạp
--     testdata đã xong, hoặc âm thầm bỏ qua nghĩa vụ chấm lại.
--
-- Vì sao nới ra ĐƯỢC mà không mất hàng rào ban đầu:
-- điều V6 thật sự sợ là ba job rejudge cùng bơm vào `judge_queue`. Từ Bước 6.3,
-- việc đó do `RejudgeJob.suatConLai` chặn, và nó đếm TOÀN BỘ số dòng priority=10
-- đang chờ trong bảng — không phải số dòng của riêng job đang chạy. Nên N job
-- rejudge chia nhau đúng một ngân sách 30% năng lực; chúng chạy chậm hơn, không
-- đông hơn. Hàng rào chuyển từ tầng "đếm job" sang tầng "đếm dòng trong hàng
-- đợi", và tầng sau mới là tầng có ý nghĩa.
--
-- Một cú double click vẫn bị chặn: cùng đề + cùng loại = cùng khoá.
-- =============================================================================

DROP INDEX ux_jobs_one_active_per_type;

-- COALESCE ba nhánh, theo đúng ba hình dạng params đang dùng:
--   REJUDGE, TESTDATA_IMPORT              -> problemId
--   LEADERBOARD_REBUILD, STANDINGS_DRIFT_CHECK -> contestId
--   HOST_BENCHMARK                        -> không có thực thể, dùng '' -> một job mỗi lúc
--
-- Nhánh '' giữ nguyên hành vi cũ cho các loại không gắn thực thể, nên thay đổi
-- này KHÔNG nới lỏng gì ngoài đúng hai loại cần nới.
CREATE UNIQUE INDEX ux_jobs_one_active_per_entity
    ON jobs (type, COALESCE(params->>'problemId', params->>'contestId', ''))
    WHERE status IN ('PENDING','RUNNING','PAUSED');

COMMENT ON INDEX ux_jobs_one_active_per_entity IS
    'Một job đang sống cho mỗi (loại, thực thể). Trần năng lực rejudge do RejudgeJob.suatConLai giữ, không do index này.';
