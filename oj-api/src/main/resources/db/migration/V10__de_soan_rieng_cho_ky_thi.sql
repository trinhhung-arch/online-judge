-- =============================================================================
-- V10 — Đề soạn riêng cho một kỳ thi
--
-- Trước V10, mọi đề của kỳ thi đều là đề "mượn" từ kho: người ra đề soạn nó ở
-- trang Đề bài rồi gắn vào kỳ thi bằng mã đề. Hệ quả là một đề luyện tập đang
-- có người giải sẽ BIẾN MẤT khỏi kho suốt thời gian nó nằm trong một kỳ thi
-- chưa kết thúc (FR-CON-03) — hai mục dính vào nhau ở chỗ không ai muốn chúng
-- dính.
--
-- Cột này ghi lại NGUỒN GỐC: đề được sinh ra cho kỳ thi này, hay được mượn vào.
-- Nó đặt trên `contest_problems` chứ không trên `problems` vì bản thân dòng
-- liên kết đã trả lời "kỳ thi nào", nên một cờ boolean là đủ — và vì `problems`
-- là bảng đọc nóng, thêm cột vào đó là trả giá ở mọi truy vấn đề.
--
-- KHÔNG thay đổi hành vi hiển thị: FR-CON-07 vẫn là "sau khi contest kết thúc,
-- mở đề ra ngoài", và điều đó đúng cho cả đề mượn lẫn đề soạn riêng. Cột này
-- nói cho GIAO DIỆN biết nó đang nhìn cái gì, không cấp thêm quyền cho ai.
-- =============================================================================

ALTER TABLE contest_problems
    ADD COLUMN created_for_contest BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN contest_problems.created_for_contest IS
    'TRUE = đề được soạn ngay trong kỳ thi này (V10). FALSE = đề mượn từ kho đề chung. '
    'Sticky: mượn rồi thì không thành soạn riêng, và ngược lại cũng vậy.';
