# 027 · Bài bị ẩn không tính điểm; ẩn hay hiện lại thì tính lại dòng của đúng người đó

Bổ sung cho [022](022-ban-chup-dong-bang-tinh-tu-bai-nop-truoc-freeze.md) — không thay thế nó.

**Bối cảnh.** FR-SUB-09 cho ADMIN ẩn bài nộp, nhưng mọi câu đọc bài để tính bảng xếp hạng bỏ qua
`hidden_at`. Bảng cộng dồn theo watermark, nên bài AC bị ẩn vẫn nằm trong điểm — kể cả khi ẩn
trước nhịp cập nhật kế tiếp. Chủ dự án chốt 2026-11-14: bài bị ẩn phải không được tính điểm.

**Quyết định.**
- Ba câu đọc bài đã chấm của `JudgingQueries` lọc `hidden_at IS NULL`, nên bài bị ẩn trước khi
  lên bảng không bao giờ được cộng, và đối soát lẫn dựng lại cũng không tính nó.
- Bài đã lên bảng thì bảng không tự biết. `HideSubmissionUseCase` báo cho mọi
  `SubmissionVisibilityListener` (cổng ở `judging.application.published`) sau khi ẩn hay hiện lại.
  `contests` hiện thực cổng ấy và gọi `StandingsUpdater.tinhLaiNguoi`: dưới khoá của kỳ thi, trong
  một transaction, xoá dòng của người đó rồi gấp lại mọi bài đã chấm, chưa ẩn, có id không quá
  watermark; nếu đã chụp đóng băng thì chụp lại dòng của người đó từ bài nộp trước `freeze_at`.

**Lý do.** Không đợi dựng lại: FR-CON-08 chỉ cho dựng lại sau giờ thi, và bài bị ẩn vì gian lận
cần biến khỏi bảng ngay. Không dựng lại cả bảng giữa giờ thi: dựng lại xoá trắng rồi tính theo lô,
người xem sẽ thấy bảng dở dang. Tính lại một người cho kết quả trùng với dựng lại từ đầu vì cả ICPC
lẫn IOI gấp bài của từng người độc lập với người khác; hạng tính lúc đọc. Cổng nằm ở `judging` và
`contests` hiện thực nó, nên chiều phụ thuộc `contests → judging` giữ nguyên.

**Hệ quả chấp nhận.**
- Tính lại hỏng thì việc ẩn vẫn thành, có log ERROR; đối soát (FR-CON-09) sẽ báo lệch và dựng lại
  sau giờ thi sửa được.
- Người bị tính lại mà không còn bài nào thì mất dòng; nếu dòng ấy đang giữ watermark, nhịp sau gấp
  lại một đoạn bài của người khác — vô hại vì phép gấp idempotent theo `last_applied_submission_id`.
- Bản chụp đóng băng của người đó được tính lại từ mọi bài nộp trước `freeze_at`, kể cả bài chấm
  xong sau lúc chụp — khớp FR-CON-05, có thể khác bản chụp gốc ở đúng những bài ấy.
