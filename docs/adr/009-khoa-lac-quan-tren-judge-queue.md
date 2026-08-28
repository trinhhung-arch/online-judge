# 009 · Khoá lạc quan đặt trên `judge_queue`, không trên `submissions`

**Bối cảnh.** Bất biến #7 của `CLAUDE.md` viết `WHERE id=? AND attempt=? AND status='JUDGING'`,
tức là khoá đặt trên `submissions`. Đây là ADR **chạm vào một bất biến**, nên nó cần cả hai
người ký (`postgres-design.md` mục 14 điểm 3).

**Quyết định.** Giữ nguyên **ngữ nghĩa**, đổi **chỗ đặt**. Câu lệnh đầu tiên của transaction ghi
verdict là `DELETE FROM judge_queue WHERE submission_id=? AND attempt=?` — 0 dòng thì bỏ qua im
lặng, không ghi gì, không báo lỗi.

**Lý do.** Muốn câu khoá trên `submissions` chạy nhanh thì phải index `status` và `attempt`.
Đo thật trên Postgres 16 với 20.000 bài nộp trọn vòng đời, `fillfactor=85`:

| Cấu hình | Update là HOT | Index sau đó |
|---|---|---|
| Không index `status` (đã chọn) | **40.000/40.000 — 100%** | 45 MB |
| Có index `status` | **0/40.000 — 0%** | 56 MB |

Mất HOT update nghĩa là ghi lại **mọi** index của bảng nóng, hai lần cho mỗi bài nộp, mãi mãi.
`judge_queue` cho cùng một bảo đảm trên một bảng vài trăm dòng.

**Hệ quả chấp nhận.** Bất biến #7 phải đọc kèm ghi chú ở `CLAUDE.md` mục 2 — chữ trong bảng đã
được cập nhật. Lớp chống trùng thứ hai là khoá chính `(submission_id, attempt)` của `judge_runs`;
lớp thứ ba là `AND status='JUDGING'` trong câu `UPDATE submissions`. Ba lớp cho một bất biến
nghe như thừa, cho tới ngày có người thêm một đường ghi verdict thứ hai.
