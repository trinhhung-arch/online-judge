# 025 · Khoá lạc quan chỉ nhận kết quả của lượt còn giữ lease

Bổ sung cho [009](009-khoa-lac-quan-tren-judge-queue.md) và
[019](019-tran-so-lan-reaper-tha-lai.md) — không thay thế chúng.

**Bối cảnh.** Khoá của ADR 009 chỉ so `attempt`, và trông vào việc lần claim sau tăng `attempt`
để loại kết quả của lượt đã chết. Nhưng reaper không tăng `attempt` (nfrplan 5.1). Từ lúc reaper
thả bài tới lúc có người nhận lại, dòng `judge_queue` vẫn mang `attempt` của lượt đã chết. Kết
quả của lượt ấy tới đúng lúc này thì thắng khoá: dòng hàng đợi bị xoá, còn `markDone` (đòi
`status = 'JUDGING'`) cập nhật 0 dòng vì reaper đã đặt `QUEUED`, và giá trị trả về bị bỏ qua.
Bài kẹt `QUEUED` ngoài hàng đợi mãi mãi, và chặn bảng xếp hạng kỳ thi phía sau nó (lô liền
mạch, ADR 019). Đã tái hiện trên Postgres thật ngày 2026-11-08 (ca KQ-04). Đường dễ gặp nhất:
API sập quá 120 s trong lúc worker còn giữ kết quả chưa gửi.

**Quyết định.**
- `RELEASE_WITH_OPTIMISTIC_LOCK` và `RETRY_IE` thêm `claimed_at IS NOT NULL`: chỉ lượt còn giữ
  lease mới ghi được. Kết quả của lượt đã bị thả về bị bỏ qua như mọi kết quả trùng, và bài được
  chấm lại.
- `writeVerdict` không còn bỏ qua giá trị trả về của `markDone`: 0 dòng thì ném
  `submission.trang_thai_khong_khop` (409), transaction rollback, dòng hàng đợi còn nguyên.

**Lý do.** Không chọn hướng "nhận kết quả muộn rồi ghi `DONE`": phải bỏ `status = 'JUDGING'`
khỏi `markDone`, tức bỏ lớp bảo vệ thứ ba của bất biến #7 (ADR 009). Không tăng `attempt` ở
reaper: số thứ tự trong `judge_runs` sẽ có lỗ (nfrplan 5.1). Điều kiện mới nằm ngay trong câu
khoá, không thêm câu SQL nào vào transaction ngắn và nóng nhất của hệ thống.

**Hệ quả chấp nhận.**
- Bài đã chấm xong mà kết quả về sau khi reaper thả thì bị chấm lại từ đầu: tốn một lượt chấm,
  đổi lại không mất bài. Kết quả về muộn nhưng trước khi reaper chạy vẫn được nhận như cũ.
- IE gửi trùng (worker gửi lại vì mất phản hồi) không còn tiêu thêm một lượt tự chấm lại.
- 409 từ `markDone` là trạng thái không được phép xảy ra. Worker coi 4xx là vĩnh viễn và bỏ bản
  ghi; reaper giao lại bài khi lease hết hạn.
