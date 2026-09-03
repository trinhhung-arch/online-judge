# Quy tắc vùng `oj-worker`

> Bổ sung cho `CLAUDE.md` ở gốc repo. Mười hai bất biến ở đó vẫn áp dụng đầy đủ.
>
> **Đây là vùng duy nhất trong dự án chạy mã của người lạ.** Mọi thay đổi ở đây được coi là thay đổi bảo mật cho đến khi chứng minh ngược lại.

---

## 1 · Sandbox — bảy điều bất di bất dịch

1. **Mọi thực thi mã người dùng đi qua `isolate`.** Không `ProcessBuilder` trần, không `Runtime.exec`, không "chạy tạm để debug". Nếu bạn thấy mình đang viết `new ProcessBuilder("g++", ...)` thì đã sai.
2. **Biên dịch cũng trong sandbox.** Compiler bomb là có thật — `#include` đệ quy và template explosion làm `g++` ăn hết RAM máy.
3. **Không mạng trong box.** Không bao giờ bật `--share-net`.
4. **Filesystem read-only trừ `/box`.** Không đọc được `/etc`, `/proc/self/*`, `/home`.
5. **Testdata không nằm trong box.** Input đi vào **chỉ qua stdin**. Nếu file test nằm trong thư mục box, một chương trình bốn dòng đọc thư mục là lộ toàn bộ đáp án.
6. **Giới hạn output.** Chương trình in 10GB ra stdout phải bị cắt, không được làm đầy đĩa của host.
7. **Không chạy bằng root.** Worker chạy user thường, mỗi box một uid riêng.

**Sau mọi thay đổi chạm vào sandbox: chạy lại toàn bộ 14 test tấn công.** Không có ngoại lệ, kể cả khi thay đổi "chỉ là refactor". Danh sách ở `nfrplan.md` 4.1.

---

## 2 · Đo thời gian

- **Đo CPU time, không phải wall time.** Máy tải nặng thì wall time làm cùng một bài lúc AC lúc TLE — mất công bằng.
- Giới hạn **cả hai**: `wall = 2 × cpu`, để chương trình ngủ hoặc chờ I/O vô hạn vẫn bị chặn.
- **Nhân `host_factor`** vào giới hạn. Máy dev là x86, host là ARM — cùng một bài chạy lệch 20–50%. Con số thời gian **chỉ có nghĩa khi đo trên host chuẩn**.
- Số judge slot là **cố định theo cấu hình**, không tự động theo số core. Máy có 10 core không có nghĩa là chạy 10 box — đọc `nfrplan.md` 2.2 về throttle nhiệt trước khi đụng vào con số này.
- Mỗi ngôn ngữ có hệ số riêng trong bảng `languages`: C++ ×1 · Python ×3–5 · Java ×2–3. JVM tốn ~100ms khởi động, phải tính đến.

---

## 3 · Không có `DataSource`

Worker biết đúng những đường dẫn trong `JudgeEndpoints` của `oj-contract`, không hơn:

```
POST /internal/judge/claim            → nhận job
POST /internal/judge/result           → trả kết quả
POST /internal/judge/benchmark        → báo phép đo tốc độ máy chấm   (M2)
POST /internal/judge/progress         → tiến độ theo lô 20 test        (M3)
GET  /internal/judge/testdata/{sha}   → tải nội dung một testcase      (M6, ADR 014)
```

`testdata` là cách worker lấy được bộ test, và là lý do câu "không MinIO client" ở dưới vẫn
đứng vững. Trước M6 câu ấy đúng nhưng **không có đường thay thế** — hiện thực `TestdataSource`
duy nhất đọc một thư mục cục bộ mà không gì đổ dữ liệu vào, nên mọi bài nộp trả `IE` ngay khi
testdata được nạp qua API. Không test nào bắt được, vì mọi test của worker tự đổ testdata vào
thư mục ấy trước khi chạy.

Hai đường dưới **không nằm trên đường `nộp bài → verdict`**: `benchmark` gọi 15 phút một lần
từ luồng lịch, `progress` là thông tin phụ. Chúng không tiêu một phần nào của ngân sách 2 giây,
và một lỗi ở đó **không được phép** làm hỏng một lượt chấm.

Không JDBC, không JPA, không Redis, không MinIO client gọi thẳng vào hạ tầng của API. Nếu một nhiệm vụ có vẻ cần worker đọc DB — **dừng lại và hỏi**, vì hầu như luôn có nghĩa là dữ liệu đó phải nằm trong `oj-contract`.

---

## 4 · Vòng đời một job

```
nhận job → tải testdata theo sha256 (cache cục bộ) → biên dịch trong box
   → chạy từng test, input qua stdin
   → gom kết quả theo lô 20 test → gửi về API
   → dọn box
```

**Quy tắc trong vòng đời này:**

- **Early exit:** test đầu tiên không phải AC thì dừng, trừ khi đề có subtask cần chấm đủ. Cắt ~50% thời gian trung bình.
- **Gửi theo lô 20 test**, không gửi từng test một. Từng test một là lỗi mà DMOJ đã mắc rồi phải thêm rate limit để cứu.
- **Cache testdata theo `sha256`** nội dung, không theo id đề. Đề sửa testdata thì hash đổi, cache tự động miss — không cần cơ chế invalidate.
- **Cache binary đã biên dịch** theo `sha256(source + lang + flags)`. Trong contest tỉ lệ trúng rất cao vì người ta nộp lại nhiều.
- **Box dir trên tmpfs.** Host có 64GB RAM, đừng ghi lên đĩa.
- **Dọn box trong `finally`.** Box rò rỉ là hết slot, và hết slot là hệ thống ngừng chấm mà không báo lỗi.

---

## 5 · Tắt máy êm — `SIGTERM`

```
nhận SIGTERM
  → ngừng nhận job mới
  → chấm nốt các job đang chạy
  → nack phần chưa bắt đầu về lại queue
  → dọn mọi box
  → thoát
```

Không có bước này thì mỗi lần deploy là mất vài bài. Và mất bài là điều duy nhất hệ thống này không được phép làm.

---

## 6 · Xử lý lỗi

| Tình huống | Hành vi đúng |
|---|---|
| Job lỗi lạ (không phải lỗi của bài nộp) | Trả `IE`, **không** nuốt lỗi. API sẽ tự chấm lại tối đa 2 lần |
| Không tải được testdata | `IE` + log. Không đoán, không chấm với dữ liệu thiếu |
| API không phản hồi khi gửi kết quả | Giữ kết quả trong buffer, retry backoff. Đừng vứt đi — reaper sẽ giao lại job và bạn vừa phí một lượt chấm |
| Box không dọn được | Log ERROR + alert. Đây là rò rỉ tài nguyên, không phải chuyện nhỏ |
| `isolate` trả mã lỗi lạ | `IE`, log nguyên văn file `meta`. Không map bừa sang `RE` |

**Không bao giờ đoán một verdict.** Nếu không chắc chắn kết quả là gì, đó là `IE` — hệ thống sẽ chấm lại. Đoán sai một verdict trong contest thì không ai phát hiện ra, và đó mới là điều tệ.

---

## 7 · Ghi log

Được log: `submissionId`, `traceId`, `attempt`, verdict, thời gian, bộ nhớ, mã lỗi `isolate`, tên file testdata theo hash.

**Không bao giờ log:** source code người dùng · nội dung testcase · nội dung stdout của chương trình người dùng · đường dẫn tuyệt đối trong box.

---

## 8 · Compatibility ARM

- Build `isolate` **trong VM Linux ARM trên host**, không copy binary từ WSL sang.
- Ưu tiên pure-Java. Thư viện native nào cũng phải có bản cho cả `amd64` và `arm64`, nếu không thì CI build multi-arch sẽ fail.
- Trước khi tin một con số thời gian: hỏi nó đo trên máy nào. Số đo trên WSL chỉ dùng để kiểm đúng/sai, không dùng để đặt giới hạn.
