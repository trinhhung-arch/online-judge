# 005 · Chấp nhận Postgres là SPOF

**Bối cảnh.** HA thật cần nhiều node, failover, quorum — hai người không vận hành nổi.

**Quyết định.** Một Postgres. Postgres chết là toàn hệ thống dừng, và ta **nói thẳng điều đó**
trong báo cáo thay vì hứa 99.99%.

**Lý do.** Đánh đổi Availability ↔ Maintainability, chọn Maintainability (`nfrplan.md` Phần 0).
Bù bằng thứ đo được: RTO ≤ 30 phút (diễn tập restore có bấm giờ ở tuần 12) và **mất dữ liệu = 0**
(`pg_dump` mỗi 15 phút + đề xuất WAL archiving).

**Hệ quả chấp nhận.** Downtime khi Postgres chết. Không read replica, không sharding —
vượt xa nhu cầu thật. Một backup chưa từng được restore không phải backup, nên buổi diễn tập
tuần 12 **không được cắt**.
