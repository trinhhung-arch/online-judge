# 006 · Host ARM (Mac M1 Max), dev x86 (WSL2)

**Bối cảnh.** Hai máy dev là Windows/WSL2 x86; máy chấm thật là Mac M1 Max ARM64.

**Quyết định.** **Máy WSL chỉ kiểm đúng/sai. Mọi con số thời gian chỉ có nghĩa khi đo trên
máy chấm chuẩn** (`judge_hosts.is_reference`, hiện là `mac-m1max-host`). Image build
multi-arch bằng `docker buildx` từ commit đầu; `isolate` build trong VM Linux ARM trên Mac,
không copy binary từ WSL.

**Lý do.** Cùng một bài chạy lệch 20–50% giữa hai kiến trúc. Không có quy tắc này thì tuần 8
sẽ có một cuộc cãi nhau kiểu *"máy tao AC mà CI báo TLE"* và mất nửa ngày mới hiểu tại sao.

**Hệ quả chấp nhận.** Cần `host_factor` và job benchmark định kỳ 15 phút; alert khi trôi > 8%.
Giới hạn thời gian của đề phải hiệu chuẩn lại nếu đổi máy chấm chuẩn.
