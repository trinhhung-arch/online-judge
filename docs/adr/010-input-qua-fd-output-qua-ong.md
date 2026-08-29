# 010 · Input vào bằng fd, output ra bằng ống — và box được dựng lại giữa mỗi test

**Bối cảnh.** M2 phải quyết bốn chuyện rất cụ thể về cách gọi `isolate`. Cả bốn đều có một
cách làm "hiển nhiên" mà mọi hướng dẫn trên mạng đều viết, và cả bốn cách hiển nhiên ấy đều
sai với hệ thống này. Số đo dưới đây lấy trên `isolate 2.6`, kernel 6.18 WSL2, x86_64.

---

## 1 · Input đi vào bằng **file descriptor thừa hưởng**, không bằng `--stdin=<file trong box>`

**Cách hiển nhiên:** copy file input vào `/box/input.txt` rồi `isolate --stdin=input.txt`. Đây
là cách CMS làm và là cách `--stdin` được thiết kế để dùng.

**Quyết định:** không truyền `--stdin` gì cả. `ProcessBuilder.redirectInput(<file trên host>)`
mở file ở phía worker; tiến trình con thừa hưởng fd đó qua `execve`.

**Lý do:** bất biến #1. Với cách hiển nhiên, một chương trình bốn dòng gọi `opendir("/box")`
lấy được tên file input; đọc nó là có nội dung test. Nộp sai có chủ ý từng test một là rút được
trọn bộ đề, rồi nộp một bảng tra cứu đáp án và AC mọi bài — và **không có dòng log nào cho
thấy chuyện đó đã xảy ra** (`frplan.md` 3.1, SEC3).

**Đo được:** với cách đã chọn, chương trình tấn công liệt kê `/box` chỉ thấy đúng một mục —
binary của chính nó — trong khi vẫn đọc được input bình thường qua `stdin`. Test tấn công 10.

---

## 2 · Output ra bằng **ống**, đọc-tới-cùng-rồi-vứt

**Cách hiển nhiên:** `--stdout=out.txt` kèm `-f` (RLIMIT_FSIZE) rồi đọc file ra. Kernel tự cắt.

**Quyết định:** ống, và `OutputLimiter` giữ `outputLimitKb` byte đầu rồi **vẫn đọc tiếp cho
tới EOF**, phần thừa vứt ngay.

**Lý do:** hai điều, và điều thứ hai mới bắt buộc.
- File output nằm trong box thì lại phải đọc nó ra, tức là thêm một đường dữ liệu đi từ box ra
  ngoài — đường đúng bằng đường mà quyết định 1 vừa đóng lại.
- "Đủ rồi thì ngừng đọc" làm ống đầy, chương trình kẹt ở `write()` và **giữ một judge slot
  cho tới hết wall-time**. Sáu bài như thế là hệ thống ngừng chấm, không lỗi, không báo.

**Đo được:** chương trình in 10GB với trần wall 3 giây — worker rút 3,1GB trong 1,2 giây rồi
`isolate` giết nó vì hết CPU; đĩa host không tăng một byte, heap worker không quá trần.
Cùng chương trình đó với `--stdout` vào file thì `SIGXFSZ` ở đúng mốc trần — đúng như quảng
cáo, nhưng file thì nằm trong box. Test tấn công 12.

**Hệ quả:** output vượt trần cho verdict **WA**, không phải RE. Chương trình không hề đổ vỡ —
nó chạy xong và in quá nhiều; và output dài hơn trần thì chắc chắn không khớp đáp án. Bộ
verdict của dự án không có `OLE`, và thêm một verdict là đổi hợp đồng.

---

## 3 · `--cleanup` + `--init` giữa **mỗi test**, không phải mỗi bài

**Cách hiển nhiên:** dựng box một lần cho cả bài nộp, chạy hết các test rồi dọn.

**Quyết định:** dựng lại trước mỗi lượt chạy, kể cả giữa bước biên dịch và test đầu tiên.

**Lý do:** `cg-mem` trong file `meta` là **đỉnh bộ nhớ của cgroup tính từ lúc box sinh ra**,
không phải của lượt chạy vừa rồi.

**Đo được:** biên dịch xong rồi chạy trong cùng một box, lượt chạy báo `cg-mem:40032` cho một
chương trình dùng thật 1,6MB — 40MB đó là đỉnh của `g++` còn sót lại. Đặt ngưỡng MLE trên con
số ấy là **mọi bài đều MLE**. Và giữa các test thì test 5 sẽ mang bộ nhớ của test 1..5 gộp
lại, nên một bài dùng 200MB ở test 1 rồi 10MB ở test 2 bị MLE ở test 2.

**Giá:** `cleanup` + `init` đo được ~5ms một lượt. Đó là giá của một con số bộ nhớ có nghĩa.

---

## 4 · `argv[0]` được tra thành đường dẫn tuyệt đối ở **phía host**

**Cách hiển nhiên:** để nguyên `g++` như trong bảng `languages` và tin rằng `-E PATH=...` lo nốt.

**Quyết định:** `CommandTemplate` tra `argv[0]` trong `oj.worker.sandbox.program-path` trước
khi dựng dòng lệnh.

**Lý do:** `isolate` gọi `execve`, **không** tra `PATH`.

**Đo được:** `--run -- g++ ...` cho `execve("g++"): No such file or directory` và
`exitcode:127`. Trên đường chấm thật thì triệu chứng là **mọi bài nộp đều RE**, với thông báo
duy nhất "Exited with error status 127" — không có gì trong đó gợi ra rằng nguyên nhân là một
đường dẫn thiếu. Lỗi này lọt qua toàn bộ unit test và chỉ lộ ra ở IT đầu tiên chạy `isolate` thật.

**Vì sao tra ở host là đúng chứ không phải tạm bợ:** `/usr` và `/bin` được `isolate` mount vào
box ở nguyên đường dẫn cũ, nên cái tìm thấy ngoài này cũng là cái chạy được trong kia. Và nó
giữ cho bảng `languages` viết được `g++` thay vì `/usr/bin/g++` — quan trọng vì ảnh ARM để
compiler ở chỗ khác ảnh x86 (C1, ADR 006).

---

## Ba điều khác được chốt cùng lúc, không đủ tầm ADR riêng

| Điều | Số đo |
|---|---|
| `/proc` và `/tmp` bị gỡ ở bước chạy | Mặc định của `isolate` **có** cả hai: `/proc/self/environ` đọc được, `/tmp` ghi được. Test tấn công 6 và 11 |
| `SG` + `cg-oom-killed:1` là **MLE**, không phải RE | Chương trình chạm 10GB bị giết bằng tín hiệu 9; báo RE là đẩy thí sinh đi tìm một lỗi con trỏ không tồn tại (U2) |
| Symlink giả làm artifact không lừa được bước copy-out | `isolate` xoá file không-thường ở cuối `--run` (`--special-files` tắt); `IsolateBox` còn kiểm `lstat` lần nữa. Test tấn công 9 |

**Chưa quyết, cần người:** ghi phép đo của `HostBenchmark` vào bảng `host_benchmarks` — worker
không có `DataSource` (bất biến #3) và `oj-contract` chưa có đường gửi phép đo về API.
