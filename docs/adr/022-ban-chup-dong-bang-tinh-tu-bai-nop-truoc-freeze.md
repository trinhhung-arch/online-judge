# 022 · Bản chụp đóng băng tính lại từ bài nộp trước `freeze_at`, đánh dấu đã chụp bằng một cột

**Bối cảnh.** `FreezeStandingsScheduler` (nhịp 5 giây) chép bảng thật sang bảng đóng băng **lúc
chụp**, nên kết quả của bài nộp sau `freeze_at` mà kịp chấm và vào bảng trước nhịp chụp thì lọt
vào bản chụp (DB-05). "Đã chụp" được suy ra từ việc bảng đóng băng có dòng: bảng còn trống lúc
`freeze_at` bị chụp lại mỗi nhịp, tới khi bài sau giờ đóng băng làm bảng có dòng (DB-06).

**Quyết định.** Cột mới `contests.frozen_captured_at` (V18; dữ liệu cũ lấy từ `captured_at`) là
dấu đã chụp, ghi trong cùng transaction với bản chụp. Bản chụp không chép bảng thật nữa: nó đọc
mọi bài đã chấm của kỳ thi, chỉ áp các bài có `created_at < freeze_at`, bằng đúng
`ContestFormat.apDung` mà bảng thật dùng, rồi ghi tổng và từng đề.

**Lý do.** Lọc theo giờ nộp là định nghĩa của đóng băng; chụp sớm hay muộn vài giây không còn
đổi kết quả. Cấm bộ cập nhật áp bài sau `freeze_at` cho tới lúc chụp thì rẻ hơn, nhưng vẫn sót:
`id` không tăng đúng theo `created_at` khi hai bài nộp sát nhau, và bài đã chấm mà bảng thật
chưa kịp áp thì vắng khỏi bản chụp.

**Hệ quả chấp nhận.** Mỗi kỳ thi có đóng băng tốn một lượt quét bài đã chấm lúc chụp (cỡ một lượt
đối soát). Bài nộp trước `freeze_at` nhưng chưa có verdict lúc chụp vẫn không có trong bản chụp,
như trước. Bản chụp chỉ gồm người có bài nộp trước giờ đóng băng.
