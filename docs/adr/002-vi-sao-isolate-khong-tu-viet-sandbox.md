# 002 · Dùng `isolate`, không tự viết sandbox

**Bối cảnh.** Hệ thống chạy mã của người lạ trên máy cá nhân của chủ dự án.

**Quyết định.** Mọi thực thi — **kể cả bước biên dịch** — đi qua `isolate` + cgroup v2.
Không `ProcessBuilder` trần, không `Runtime.exec`, không "chạy tạm để thử".

**Lý do.** Sandbox tự viết *sẽ* có lỗ hổng, và ta không biết cho tới khi bị khai thác. Đây là
rủi ro #1 của cả dự án (`nfrplan.md` Phần 13): hậu quả vượt ra ngoài phạm vi đồ án. Compiler
bomb là có thật, nên compile cũng phải nằm trong box.

**Hệ quả chấp nhận.** ~10–30ms overhead mỗi lần chạy; phải build `isolate` cho ARM trong VM
Linux trên Mac. `IsolateJudgeRunner` chỉ được thay `ScriptedJudgeRunner` **đúng ngày 14/14
test tấn công xanh trong CI**, không sớm hơn một giờ.
