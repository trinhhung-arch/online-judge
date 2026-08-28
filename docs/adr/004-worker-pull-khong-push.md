# 004 · Worker PULL, server không PUSH

**Bối cảnh.** Cần giao việc cho N worker chạy trên nhiều máy khác nhau.

**Quyết định.** Worker gọi `POST /internal/judge/claim` để xin việc. Server **không** giữ danh
sách worker, không heartbeat, không service discovery.

**Lý do.** S2 = *"worker mới join không cần sửa config API: 0 thao tác"*. Bật thêm worker là nó
tự vào việc. `FOR UPDATE SKIP LOCKED` lo phần không-giao-trùng mà không cần khoá phân tán nào.
Đây cũng là thứ làm bài test scalability tuần 12 (Mac + hai WSL) không tốn đồng nào.

**Hệ quả chấp nhận.** Có độ trễ giao việc bằng một nhịp poll cho tới khi RabbitMQ vào ở M6.
Server không biết có bao nhiêu worker đang sống — số liệu đó lấy từ `judge_hosts.last_seen_at`,
cập nhật ngoài đường nóng (M6).
