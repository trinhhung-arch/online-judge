# ADR — Architecture Decision Record

Mỗi quyết định lớn = một file ~10 dòng: **bối cảnh · lựa chọn · lý do · hệ quả chấp nhận**
(`nfrplan.md` 8.3). Chi phí 15 phút/file; lợi ích là tháng sau không ai lôi ra bàn lại, và
hội đồng hỏi *"sao em không dùng Kafka"* thì đã có câu trả lời viết sẵn.

Quy tắc: ADR **không sửa, chỉ thay thế**. Đổi ý thì viết file mới và ghi `Thay thế: 00x`.

| # | Quyết định | Mốc |
|---|---|---|
| [001](001-hang-doi-postgres-roi-rabbitmq.md) | Hàng đợi ở Postgres trước, RabbitMQ sau | M1 |
| [002](002-vi-sao-isolate-khong-tu-viet-sandbox.md) | Dùng `isolate`, không tự viết sandbox | M2 |
| [003](003-vi-sao-modular-monolith-khong-microservices.md) | Modular monolith, không microservices | M0 |
| [004](004-worker-pull-khong-push.md) | Worker tự đi xin việc (pull), không bị đẩy | M1 |
| [005](005-chap-nhan-postgres-la-spof.md) | Chấp nhận Postgres là điểm hỏng duy nhất | M0 |
| [006](006-host-tren-mac-arm-dev-tren-wsl-x86.md) | Host ARM trên Mac, dev x86 trên WSL | M0 |
| [007](007-ai-review-bat-dong-bo-va-tat-trong-contest.md) | AI review bất đồng bộ, tắt trong contest | M6 |
| [008](008-6-judge-slot-thay-vi-9-vi-nhiet.md) | 6 judge slot thay vì 9, vì throttle nhiệt | M1 |
| [009](009-khoa-lac-quan-tren-judge-queue.md) | Khoá lạc quan trên `judge_queue` | M1 |
| [010](010-input-qua-fd-output-qua-ong.md) | Input vào bằng fd, output ra bằng ống, box dựng lại giữa mỗi test | M2 |
| [011](011-redis-pubsub-tu-M3-khong-doi-M4.md) | Redis pub/sub cho SSE làm ở M3, không đợi M4 | M3 |
| [012](012-tu-viet-jwt-hs256-thay-vi-them-thu-vien.md) | Tự viết JWT HS256 bằng JDK, không thêm thư viện JOSE | M4 |
| [013](013-rabbitmq-la-chuong-cua-khong-phai-goi-viec.md) | RabbitMQ là chuông cửa, không phải gói việc — message chỉ mang `submissionId` | M6 |
