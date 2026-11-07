# 021 · Bảng xếp hạng chốt khi hết bài chờ chấm, không dừng cứng sau 5 phút

**Bối cảnh.** `StandingsUpdater` chỉ cập nhật kỳ thi có `ends_at > bây giờ − standings-grace`
(5 phút). Bài nộp trước giờ kết thúc mà chấm xong muộn hơn — hàng đợi dồn cuối giờ, worker chết
và reaper đi hết ba lease (ADR 019, ~6 phút) — không bao giờ vào bảng, cùng mọi bài sau nó (lô
liền mạch). Đối soát chỉ báo lệch, không sửa (ca BX-07).

**Quyết định.** Cột mới `contests.standings_settled_at` (V17). Kỳ thi được cập nhật từ lúc bắt
đầu cho tới khi **chốt**. Chốt khi: đã quá `standings-grace`, **không** còn bài nào của kỳ thi trong
`judge_queue` (kiểm **trước** lượt cập nhật, qua `JudgingQueries.conBaiChoChamTrongContest`), và
lượt cập nhật không tính thêm bài nào. V17 đánh dấu sẵn đã chốt cho mọi kỳ thi kết thúc quá 5 phút.

**Lý do.** Bài chưa có verdict ⇔ còn dòng trong `judge_queue` (nộp bài, ghi verdict, reaper đều
đổi hai thứ trong cùng transaction — bất biến #7). Sau `ends_at` không bài mới nào được gắn vào kỳ
thi, nên "hàng đợi trống rồi một lượt không tính thêm gì" nghĩa là bảng đã đủ. `judge_queue` chỉ
vài trăm dòng; kiểm bằng nó rẻ hơn quét `submissions` (không có index theo `contest_id`).

**Hệ quả chấp nhận.** Kỳ thi kết thúc mà còn bài kẹt hàng đợi (worker tắt cả giờ) được quét lại
mỗi 2 giây tới khi chấm xong. Chấm lại **sau** khi đã chốt vẫn không tự vào bảng — vẫn là đối soát
+ dựng lại như trước (ca DS-04).
