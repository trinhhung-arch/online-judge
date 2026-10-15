-- =============================================================================
-- V16 — Trần số lần reaper thu hồi một bài (FR-SUB-03, ADR 019)
--
-- ★ VÌ SAO CẦN TRẦN
-- Reaper thả bài hết lease 120s về hàng đợi VÔ HẠN lần. Một bài làm sập worker, hay một đề
-- mất testdata ở đúng phiên bản bài đã đóng dấu (ClaimJudgeJobUseCase.skipBrokenJob), quay
-- vòng mãi: treo ở JUDGING trước mắt người nộp, tốn một lease mỗi vòng, và — vì bảng xếp hạng
-- tính theo lô LIỀN MẠCH (baiDaChamLienMach) — chặn luôn bảng xếp hạng kỳ thi phía sau nó.
-- nfrplan 5.1 đã hứa "bài lỗi lạ không được quay vòng vô hạn"; ADR 013 dời việc giao lại từ
-- RabbitMQ (DLQ sau 3 lần) sang judge_queue + reaper mà không mang theo cái trần ấy.
--
-- ★ VÌ SAO KHÔNG DÙNG `attempt`
-- Rejudge chèn hàng với attempt = submissions.attempt — đánh số TIẾP từ lượt chấm trước.
-- Một bài đã rejudge vài lần sẽ "chạm trần" ngay ở lần hết lease đầu tiên. Bộ đếm phải sống
-- và chết cùng MỘT hàng judge_queue: hàng mới (nộp bài, rejudge) bắt đầu từ 0, hàng bị xoá
-- (ghi verdict) mang nó đi theo.
--
-- ★ NULLABLE CÓ MẶC ĐỊNH, KHÔNG NOT NULL — oj-api/CLAUDE.md §7, cùng cách V15 đã làm. Mọi câu
-- đọc/ghi coi NULL là 0 (COALESCE trong JdbcJudgeQueueRepository). Quyền của oj_app đã cấp ở
-- mức BẢNG nên phủ luôn cột mới.
-- =============================================================================

ALTER TABLE judge_queue
    ADD COLUMN reap_count SMALLINT DEFAULT 0 CHECK (reap_count IS NULL OR reap_count >= 0);

COMMENT ON COLUMN judge_queue.reap_count IS
    'Số lần reaper đã thả bài này về hàng đợi vì hết lease. Chạm oj.judge.max-reaps thì lần hết lease kế tiếp ghi IE thay vì thả lại — V16, ADR 019';
