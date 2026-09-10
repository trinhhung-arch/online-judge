-- =============================================================================
-- V12 — Thứ tự đề trong kỳ thi CHÍNH LÀ cái nhãn
--
-- ★ VÌ SAO BỎ MỘT CỘT THAY VÌ THÊM MỘT RÀNG BUỘC
-- V7 cho `contest_problems` hai cột cùng nói một chuyện: `label` ('A', 'B') là thứ thí sinh
-- nhìn thấy, `ordinal` (số) là thứ dùng để ORDER BY. Người gọi API phải truyền cả hai và tự
-- giữ cho chúng khớp nhau — không có gì ép. Hệ quả có hai tầng:
--
--   * Tầng khó hiểu: đặt được label='D', ordinal=1 và label='A', ordinal=2. Lúc đó "bài A"
--     không phải bài đầu tiên, và không ai sai cả — cả hai cột đều hợp lệ.
--   * Tầng hỏng thật: `label` có UNIQUE (contest_id, label), `ordinal` KHÔNG có ràng buộc
--     nào. Hai đề cùng ordinal là chuyện đã xảy ra trong dữ liệu dev, và
--     `ORDER BY cp.ordinal` trần thì SQL không hứa gì về thứ tự giữa hai dòng bằng nhau.
--     Giữa kỳ thi mà "bài B" đổi nghĩa giữa hai lần tải trang là chuyện công bằng.
--
-- ★ VÌ SAO NHÃN ĐỦ SỨC LÀM THỨ TỰ MỘT MÌNH
-- Nhãn không phải chuỗi tự do: `AuthorContestUseCase.NHAN` ép nó về ^[A-Z]{1,2}$, và
-- UNIQUE (contest_id, label) cấm trùng trong cùng kỳ thi. Hai điều đó cộng lại cho một thứ
-- tự TOÀN PHẦN và xác định khi sắp bằng:
--
--     ORDER BY length(label), label      -->  A, B, ..., Z, AA, AB, ...
--
-- `length` đứng trước là phần bắt buộc: sắp theo chữ cái trần thì 'AA' < 'B', tức là AA chen
-- vào giữa A và B — sai với thứ tự ICPC. Đây cũng là lý do một câu ORDER BY chỗ khác trong
-- codebase KHÔNG được rút gọn thành `ORDER BY label`.
--
-- ★ THỨ MẤT ĐI, VÀ KHI NÀO PHẢI TRẢ NÓ VỀ
-- Mất khả năng đổi thứ tự mà không đổi nhãn. Với thể thức ICPC/IOI thì nhãn CHÍNH LÀ thứ tự,
-- nên đổi thứ tự tức là đổi nhãn — không mất gì thật. Ngày nào nhãn được phép là chuỗi tự do
-- ('Bài 10' phải đứng sau 'Bài 9') thì cột thứ tự phải quay lại. Xem ADR 015.
-- =============================================================================

ALTER TABLE contest_problems DROP COLUMN ordinal;

COMMENT ON COLUMN contest_problems.label IS
    'Nhãn ICPC A..Z rồi AA..ZZ. Vừa là tên đề trong kỳ thi, VỪA LÀ THỨ TỰ — '
    'sắp bằng ORDER BY length(label), label. Không có cột thứ tự riêng: ADR 015.';
