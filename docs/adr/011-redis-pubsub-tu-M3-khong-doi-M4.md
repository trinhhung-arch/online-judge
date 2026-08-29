# 011 · Redis pub/sub cho SSE làm ở M3, không đợi M4

**Bối cảnh.** `nfrplan.md` xếp "SSE fan-out qua Redis pub/sub" vào tuần 7 (M4), còn FR-SUB-05
(verdict realtime) nằm ở tuần 5 (M3). Khoảng cách hai tuần đó buộc phải có một hiện thực tạm.

**Quyết định.** Làm thẳng `RedisSubmissionEventBus` ở Bước 3.8, tuần 5. Thêm
`spring-boot-starter-data-redis` vào `oj-api/pom.xml` ở compile scope.

**Lý do.**

1. **Không phải rước một thư viện lạ.** Redis đã có trong `docker-compose.yml` từ M0,
   `spring.data.redis` đã nằm trong `application.yml` từ đó, và Lettuce 7.5.2 đã ở trong cây
   phụ thuộc — chỉ ở test scope, kéo theo bởi `spring-boot-starter-data-redis-test`. Đây là
   đổi phạm vi một dòng.
2. **Chi phí như nhau ở tuần 5 hay tuần 7** (~4h), nhưng làm bản in-memory trước là **viết
   hai lần**.
3. **Bản in-memory sai ngay từ định nghĩa.** Instance API đang giữ kết nối SSE của một người
   không phải instance nhận verdict từ worker — worker gọi `/internal/judge/result` qua load
   balancer. Chạy 2 instance với danh sách kết nối trong bộ nhớ là **50% người dùng không bao
   giờ nhận được gì**, và triệu chứng là "thỉnh thoảng trang không tự cập nhật" — thứ không ai
   tái hiện được, và sẽ được báo cáo lần đầu vào giữa một contest.

**Hệ quả chấp nhận.**

- `oj-api` phụ thuộc Redis lúc chạy. Nhưng **Redis chết không làm hỏng gì cả**: `publish` nuốt
  mọi lỗi (verdict đã commit rồi mới đẩy tin), và `RedisMessageListenerContainer` chỉ mở kết
  nối khi có người mở luồng SSE — nên Redis chết lúc khởi động không ngăn API nhận bài nộp.
  Mất realtime là mất tiện nghi; mất khả năng nhận bài nộp là mất bài.
- Fallback REST (Bước 3.10) vẫn **bắt buộc**, không phải vì Redis mà vì Cloudflare Tunnel cắt
  kết nối dài. Nó cũng chính là đường vá cho mọi sự cố Redis.
- M5 dùng lại đúng kết nối này cho leaderboard, nên chi phí hạ tầng bằng 0 ở đó.

**Điều đã KHÔNG chọn, và vì sao.** Postgres `LISTEN/NOTIFY` không cần dependency nào, nhưng nó
chiếm một connection thường trực cho mỗi instance API — trên một hệ thống đã cố ý chia hai pool
vì sợ cạn connection (`postgres-design.md` mục 11) — và M5 vẫn sẽ cần Redis cho leaderboard.
Rốt cuộc vẫn phải thêm, chỉ là muộn hơn và sau khi đã viết một lần.
