# 008 · 6 judge slot thay vì 9, vì nhiệt

**Bối cảnh.** M1 Max có 8 P-core + 2 E-core. Bản năng là chạy 9 box song song cho hết công suất.

**Quyết định.** **6 slot cố định theo cấu hình**, không tự động theo số core. Cộng job benchmark
mỗi 15 phút ghi `host_benchmarks`, alert khi `host_factor` trôi > 8%.

**Lý do.** M1 Max trong thân máy laptop chạy full core 10–15 phút sẽ throttle. Trong contest 2
tiếng, bài phút thứ 90 chấm chậm hơn bài phút thứ 5 → **mất tính công bằng ngay giữa cuộc thi**,
và không ai nhận ra. Đánh đổi Throughput ↔ Công bằng thời gian, chọn công bằng.

**Hệ quả chấp nhận.** Throughput ~5 bài/s thay vì cao hơn. Muốn nhanh hơn thì **thêm máy**, và
kiến trúc đã sẵn sàng cho điều đó — đó mới là điều đáng nói khi bảo vệ.
