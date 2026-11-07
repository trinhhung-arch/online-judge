# 023 · ICPC phạt theo phút làm tròn xuống; IOI quy đổi điểm theo `points` của đề

**Bối cảnh.** ICPC cộng thời gian tới lúc AC tính bằng **giây**, trong khi giao diện hiện **phút**:
hai đội cùng hiện "37 phút" vẫn bị xếp khác hạng, và thứ hạng có thể đảo so với luật ICPC (giải lúc
0:59 + 1:59 là 1 phút, lúc 1:01 + 1:01 là 2 phút) — ca TD-03. IOI thì `contest_problems.points` có
chỗ nhập, có chỗ hiện, nhưng bảng cộng thẳng điểm thô của bài nộp: đề ghi 200 điểm làm đúng hết
chỉ được 100, đề có thang subtask 50 làm đúng hết chỉ được 50 — ca TD-06.

**Quyết định.** ICPC: thời gian tới lúc AC của **từng đề** làm tròn xuống phút rồi mới cộng
(`toMinutes() * 60`; cột vẫn lưu giây, chỉ là bội của 60). IOI: điểm đề trên bảng
= ⌊`score` × `points` / `max_score`⌋; không biết thang (`max_score` = 0) hoặc `points` thì giữ
điểm thô. Cả bảng thật, đối soát và bản chụp đóng băng dựng bài chấm qua cùng một chỗ
(`BaiChamKyThi`), nên ba đường không thể lệch nhau. Quyết định của chủ dự án ngày 2026-11-07.

**Lý do.** Thể thức mang tên ICPC thì phải theo luật ICPC, và thứ hạng phải khớp con số người ta
nhìn thấy. `points` đã là lời hứa trên giao diện; IOI thường cho mỗi đề một trọng số.

**Hệ quả chấp nhận.** Đổi luật chấm: không triển khai giữa một kỳ thi đang chạy. Kỳ thi cũ có AC
lẻ giây (ICPC) hay `points` khác thang đề (IOI) sẽ bị đối soát báo lệch — `RebuildStandingsJob`
dựng lại nếu muốn áp luật mới. Nhiều cặp bằng phút hơn; hai đội bằng phút phân bằng giờ AC cuối,
tiêu chí đã có sẵn trong thứ tự xếp.
