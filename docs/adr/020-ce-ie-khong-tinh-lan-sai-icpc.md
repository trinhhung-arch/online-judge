# 020 · ICPC: chỉ WA, TLE, MLE, RE tính là lần sai — CE và IE không bị phạt

Thay thế: mục ⚠️ "Công bằng, chưa quyết" của [019](019-tran-so-lan-reaper-tha-lai.md).

**Bối cảnh.** `IcpcFormat.apDung` cộng một lần sai (thêm `penalty_minutes`) cho **mọi** verdict
khác AC trước lần AC đầu tiên. `IE` là lỗi của máy chấm (reaper bỏ cuộc sau ba lease, ADR 019):
thí sinh bị phạt cho sự cố của hệ thống. `CE` thì luật ICPC chuẩn cũng không tính.

**Quyết định.** Chỉ `WA`, `TLE`, `MLE`, `RE` tính là lần sai (`IcpcFormat.TINH_LA_LAN_SAI`).
`CE` và `IE` vẫn được áp lên bảng (đánh dấu đã tính, để chạy lại cùng lô không đếm thêm) nhưng
không cộng phạt. `ContestFormat.BaiDaCham` mang verdict thay cho cờ `laAc`, để luật nằm trong
domain chứ không rải ở chỗ gọi. Ca TD-04, quyết định của chủ dự án ngày 2026-11-07.

**Lý do.** Công bằng là thứ hệ thống này bán: không ai mất điểm vì máy chấm hỏng hay vì gõ sai
cú pháp — đúng như các bảng ICPC thông dụng.

**Hệ quả chấp nhận.** Đổi luật chấm: bảng của các kỳ thi **cũ** có CE/IE trước AC sẽ lệch so với
cách tính mới — đối soát (FR-CON-09) sẽ báo, `RebuildStandingsJob` dựng lại nếu muốn áp luật mới.
Không triển khai bản này giữa một kỳ thi đang chạy.
