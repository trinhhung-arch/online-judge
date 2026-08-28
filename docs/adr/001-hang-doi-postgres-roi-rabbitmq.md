# 001 · Hàng đợi sống trong Postgres, RabbitMQ chỉ là đường dẫn

**Bối cảnh.** Cần giao bài nộp cho worker. Bản năng là đẩy thẳng vào RabbitMQ và coi queue
là nơi chứa việc.

**Quyết định.** Bảng `judge_queue` trong Postgres là **sự thật**. RabbitMQ (từ M6) chỉ mang
`submissionId` để rút ngắn độ trễ từ "một nhịp poll" xuống vài mili giây. M1 chưa có
RabbitMQ: `NoopJudgeEventPublisher` chỉ ghi log, worker tự PULL.

**Lý do.** R1 nói *0 bài mất là tuyệt đối*. Queue chết mà là kho chứa thì mất bài; queue chết
mà chỉ là đường dẫn thì hàng vẫn nằm trong DB và reaper nhặt lại. Dựng lại toàn bộ hàng đợi
là một câu `SELECT` trên bảng vài trăm dòng (`docs/sql/duong_nong.sql` truy vấn 5).

**Hệ quả chấp nhận.** Một bảng nữa phải chăm (autovacuum riêng, `fillfactor=70`), và trạng
thái sống ở hai chỗ — `submissions.status` là ảnh chụp, `judge_queue` là sự thật. Đổi transport
ở Bước 6.4 chỉ chạm `JudgeJobPublisher` và consumer; nếu nó đụng nhiều hơn hai file thì M1 đã sai.
