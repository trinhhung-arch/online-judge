# 003 · Modular monolith, không microservices

**Bối cảnh.** Team 2 người, 13 tuần, host là một máy Mac ở nhà.

**Quyết định.** Một `oj-api` với 6 module trong cùng tiến trình (`platform`, `identity`,
`problems`, `judging`, `contests`, `ai`), cộng `oj-worker` tách rời. Ranh giới module ép bằng
ArchUnit chứ không bằng ranh giới mạng.

**Lý do.** 95% tải của một OJ là chấm bài, và đó là chiều duy nhất cần scale — `oj-worker`
đã tách sẵn. Microservices thêm network, deploy, tracing phân tán và một lớp lỗi mới, đổi lấy
khả năng scale phần không cần scale. Hai người sẽ chết chìm.

**Hệ quả chấp nhận.** Deploy API là all-or-nothing (~30s downtime, chấp nhận được). Ranh giới
module chỉ tồn tại nếu ArchUnit còn xanh — nới một luật là mất luôn lợi ích của quyết định này.
