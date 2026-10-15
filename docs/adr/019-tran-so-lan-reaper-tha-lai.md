# 019 · Reaper có trần: hết lease lần thứ 3 thì ghi IE, không quay vòng mãi

Bổ sung cho [013](013-rabbitmq-la-chuong-cua-khong-phai-goi-viec.md) — không thay thế nó.

**Bối cảnh.** Reaper thả bài hết lease 120s về hàng đợi **vô hạn lần**. Bài làm sập worker, hay
đề mất testdata ở đúng phiên bản bài đã đóng dấu (`ClaimJudgeJobUseCase.skipBrokenJob`), quay
vòng mãi: treo `JUDGING` trước mắt người nộp, tốn một lease mỗi vòng, và — vì bảng xếp hạng tính
theo lô **liền mạch** — chặn luôn bảng xếp hạng kỳ thi phía sau nó. `nfrplan.md` 5.1 mục 4 đã hứa
"bài lỗi lạ không được quay vòng vô hạn" bằng "DLQ sau 3 lần"; ADR 013 dời việc giao lại từ
RabbitMQ sang `judge_queue` + reaper mà không mang theo cái trần ấy.

**Quyết định.** Chủ dự án chốt "trần 3 lần" (2026-10-15). Cột `judge_queue.reap_count` (V16) đếm
số lần reaper thả bài; `oj.judge.max-reaps: 2`. Còn lượt thì thả như cũ. Hết lượt thì reaper
**bỏ cuộc**: mỗi bài một transaction qua `RecordJudgeResultUseCase.boCuoc` — câu đầu tiên vẫn là
`DELETE judge_queue … AND attempt=?` (bất biến #7), rồi `judge_runs` (`IE`, máy `reaper`,
`host_id` NULL), rồi `submissions` `DONE`, rồi sự kiện SSE. Không rẽ nhánh FR-SUB-12: thả lại vì
IE chính là vòng quay vừa bị chặn.

**Lý do.** Không đếm trên `attempt`: rejudge chèn hàng với `attempt` đánh số tiếp, nên bài đã
rejudge sẽ chạm trần ngay lần hết lease đầu. Không để reaper tự viết câu ghi verdict: đó là đường
ghi thứ hai, thứ ghi đè được kết quả thật. Qua khoá lạc quan thì ai tới trước thắng — worker trả
kịp thì lượt bỏ cuộc im lặng thành `IGNORED`, trả muộn thì kết quả ấy bị từ chối như mọi attempt
đã chết. `IE` là verdict nói "không phải lỗi bài của bạn" (`VerdictExplainer`).

**Hệ quả chấp nhận.**
- Bài chỉ **chậm** quá lease ba lần liên tiếp (đề nặng hơn 120s) cũng nhận `IE`. Trước đây nó quay
  vòng mãi và cũng chẳng bao giờ có verdict; dấu hiệu là WARN "vượt lease" phía worker — nâng lease.
- Quy tắc cũ của reaper "**không ghi verdict**" thành "**không tự ghi verdict**".
- ⚠️ **Công bằng, chưa quyết:** ở ICPC mọi verdict khác AC, kể cả `IE`, đều tính một lần sai
  (`IcpcFormat`) — thí sinh bị cộng penalty cho một sự cố máy chấm. Điều này có từ trước (IE sau
  FR-SUB-12), V16 chỉ mở thêm một đường tới nó. Đổi là đổi luật chấm của kỳ thi: phải hỏi người.
