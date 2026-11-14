# 026 · Lease được gia hạn theo tiến độ: reaper bắt worker im lặng, không bắt bài chấm lâu

Bổ sung cho [019](019-tran-so-lan-reaper-tha-lai.md) và
[025](025-khoa-lac-quan-chi-nhan-luot-con-giu-lease.md) — không thay thế chúng. Thay một
"hệ quả chấp nhận" của 019: *"bài chỉ chậm quá lease ba lần liên tiếp cũng nhận `IE`"*.

**Bối cảnh.** Lease tính 120 s từ lúc `claim` và không bao giờ được gia hạn, nên reaper không
phân biệt được worker chết với bài chấm lâu. Đề 100 test giới hạn 1 s: bài Python (hệ số 4,0)
đúng mà dùng nửa giới hạn chạy 200 s — bị thu hồi ba lần rồi nhận `IE`, chiếm tới 3 slot, trong
khi bài C++ cùng đề AC bình thường. Đó là mất công bằng giữa các ngôn ngữ. DB thật ngày
2026-11-14 chỉ có 1 đề 3 test (lần chấm lâu nhất 1,2 s) nên chưa ai dính. Cùng gốc ấy còn một
lỗi hiển thị: tiến độ của lượt đã bị thu hồi vẫn được đẩy ra trình duyệt (ca test B11).

**Quyết định.** Chủ dự án chốt 2026-11-14.
- `POST /internal/judge/progress` gia hạn lease: `RENEW_LEASE` đặt `lease_until = now() + lease`
  với điều kiện `attempt = ?` và `claimed_at IS NOT NULL` — đúng định nghĩa "lượt còn giữ lease"
  của ADR 025. Khớp 0 dòng thì bỏ qua im lặng và **không** phát sự kiện SSE; vẫn trả 204.
- Worker gửi một lô khi đủ 20 test **hoặc** khi đã quá `oj.worker.progress-interval` (30 s) kể từ
  lô trước. Lô vẫn có ít nhất một test, nên `oj-contract` không đổi một chữ.
- Con số 120 s giữ nguyên; nghĩa đổi từ "120 s kể từ lúc nhận bài" thành "120 s không có tín hiệu".

**Lý do.** Chỉ gia hạn khi **một test vừa chạy xong**, không dùng bộ hẹn giờ: worker treo (kẹt
tải testdata, deadlock) không gửi được gì nên vẫn bị thu hồi, còn bộ hẹn giờ thì giữ lease cho
cả bài đã treo. Số test có hạn và mỗi test bị isolate cắt, nên không bài nào giữ lease mãi.
Không nâng lease: mọi bài, kể cả bài nhẹ, chờ lâu hơn khi worker chết. Không chặn đề nặng lúc
xuất bản: trường hợp xấu nhất của một đề 100 test × 1 s đã là 400 s với Python. Không thêm
`lease_until > now()` vào câu gia hạn: isolate 2.6 đo thời gian thực bằng `CLOCK_MONOTONIC`, đồng
hồ ấy đứng yên khi máy ngủ, nên một test vắt qua lúc ngủ vẫn được đo đúng và bài còn sống không
cần chấm lại. Đo trên Postgres 16 (2026-11-14): 0,37 ms mỗi lần gia hạn, khoảng 2700 lần/giây với
6 kết nối; nhu cầu thật dưới 10 lần/giây.

**Hệ quả chấp nhận.**
- Một **test đơn lẻ** chạy gần hết lease vẫn bị thu hồi, vì tín hiệu chỉ đi ở ranh giới giữa hai
  test. Với cấu hình hiện tại: đề giới hạn trên khoảng 13 s và bài Python treo tới hết giới hạn
  thời gian thực (2 × CPU). Chưa có chốt lúc lưu đề; bài như vậy vẫn đi đường `IE` của ADR 019.
- Mỗi lần gia hạn ghi index `ix_judge_queue_lease` (không HOT). Chỉ trên `judge_queue`; bảng nóng
  `submissions` không bị đụng. Autovacuum giữ bảng ổn định ở khoảng 1 MB sau 44 nghìn lần ghi.
- Sau khi reaper thu hồi, trang bài nộp vẫn hiện con số tiến độ cuối cho tới lô đầu của lượt
  mới: reaper chưa phát sự kiện `QUEUED`.
- Worker đã mất lease (API sập quá 120 s) vẫn chạy nốt bài và chiếm slot; kết quả bị ADR 025 loại.
- WARN "vượt lease" của `JudgeLoop` bị gỡ: chấm lâu hơn lease không còn là dấu hiệu sự cố.
