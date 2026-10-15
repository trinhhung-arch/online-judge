# 018 · Mọi lượt ghi bảng xếp hạng của một kỳ thi xếp hàng sau một khoá tư vấn

**Bối cảnh.** `StandingsUpdater.capNhat` đọc watermark và lô bài **ngoài** transaction, rồi mới
mở transaction để ghi. Transaction làm cho một lô nguyên tử, không làm cho hai lô tuần tự. Hai
lượt cùng kỳ thi — nhịp 2 giây và `RebuildStandingsJob`, hoặc hai instance API (`nfrplan.md`
Phần 14) — từng đọc chung một watermark, và lượt sau áp lại cả lô lên trạng thái lượt trước vừa
ghi: ICPC đếm một lần sai thành hai (thêm `penalty_minutes`). Job dựng lại xoá bảng chen vào giữa
một lô thì watermark nhảy qua những bài vừa bị xoá — chúng biến khỏi bảng. Cả hai tái hiện được
trên Postgres thật (`BangXepHangGhiSongSongIT`, đỏ `2` và `0` trên bản cũ).

**Quyết định.** Mọi đường ghi `contest_standings` / `contest_problem_standings` giữ
`pg_advisory_xact_lock(vùng "STND", id kỳ thi)` **trước** mọi câu đọc, trong cùng transaction
với lượt ghi (`StandingsLock`). Hôm nay có hai đường, cả hai trong `StandingsUpdater`: `capNhat`
và `xoaTrang`. Lớp thứ hai: `IcpcFormat.apDung` bỏ qua bài có id ≤ bài cuối đã tính của đề ấy.

**Lý do.** Khoá dòng `contests` (`FOR UPDATE`) cũng tuần tự hoá được, nhưng nó chặn luôn kiểm
tra khoá ngoại của `INSERT submissions` — đường nộp bài, đúng lúc kỳ thi đông nhất. Khoá tư vấn
chỉ chặn ai xin đúng nó. Khoá phải là **câu lệnh riêng, đứng trước**: ở READ COMMITTED mỗi câu
chụp ảnh dữ liệu lúc nó bắt đầu, nên câu đọc watermark phải chạy sau khi đã giữ khoá.

**Hệ quả chấp nhận.** Hai lượt cùng kỳ thi giờ chờ nhau — vài mili giây mỗi lô, chỉ khi thật sự
trùng. Ai thêm một đường ghi bảng xếp hạng thứ ba phải nhớ giữ khoá; `JdbcStandingsLock` ném lỗi
nếu bị gọi ngoài transaction của pool `app`, nhưng không gì ép được việc *quên gọi*. Bản chụp
đóng băng (`chupDongBang`) vẫn là hai câu tự commit không khoá — ngoài phạm vi ADR này.
