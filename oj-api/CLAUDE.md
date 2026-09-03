# Quy tắc vùng `oj-api`

> Bổ sung cho `CLAUDE.md` ở gốc repo. Mười hai bất biến ở đó vẫn áp dụng đầy đủ.

---

## 1 · Đường nóng — `POST /api/v1/submissions`

Đây là đoạn code quan trọng nhất hệ thống. Ngân sách **300ms**, và nó không được phép hỏng khi mọi thứ khác hỏng.

**Chuỗi bắt buộc, đúng thứ tự này:**

```
validate input  →  INSERT submissions (status=QUEUED)  →  COMMIT
                                                            ↓
                                              publish submissionId lên RabbitMQ
                                                            ↓
                                                    return 202 + submissionId
```

**Trong handler này KHÔNG ĐƯỢC có:**

- Bất cứ lời gọi nào tới worker
- Bất cứ chờ đợi nào: `.get()`, `.join()`, `await`, `Thread.sleep`
- Publish RabbitMQ **bên trong** transaction — nếu commit fail sau khi publish, worker sẽ nhận một submission không tồn tại
- Gọi LLM, gọi MinIO, render Markdown, hay bất cứ I/O nào không bắt buộc
- Query đếm (`SELECT COUNT(*)`) để hiển thị thống kê

**Publish thất bại thì sao?** Không rollback, không ném lỗi ra người dùng. Ghi log ở mức WARN và trả về 202 bình thường — submission đã nằm trong DB với `status=QUEUED`, và **reaper sẽ nhặt nó lên**. Đây là lý do reaper tồn tại.

---

## 2 · Ma trận hiển thị — kiểm ở use-case, không phải controller

Trước khi viết bất kỳ endpoint đọc dữ liệu nào, xác định ô tương ứng trong bảng này:

| Dữ liệu | GUEST | USER | Tác giả bài nộp | SETTER (đề của mình) | ADMIN |
|---|---|---|---|---|---|
| Đề đã xuất bản | ✅ | ✅ | ✅ | ✅ | ✅ |
| Đề trong contest chưa mở | ❌ | ❌ | ❌ | ✅ | ✅ |
| Testcase `sample` | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Nội dung testcase ẩn** | ❌ | ❌ | **❌** | ✅ | ✅ |
| Số thứ tự test bị fail | — | — | theo `feedback_level` | ✅ | ✅ |
| Log compiler | — | — | ✅ | ✅ | ✅ |
| Source người khác — ngoài contest | ❌ | theo cấu hình đề | — | ✅ | ✅ |
| Source người khác — **trong contest** | ❌ | **❌** | — | **❌** | ✅ |
| Bảng xếp hạng đã freeze | ❄️ | ❄️ | ❄️ | ❄️ | ✅ đầy đủ |
| AI review | — | — | ✅ ngoài contest | ❌ | ✅ |
| `audit_log` | ❌ | ❌ | ❌ | ❌ | ✅ |

**Ô quan trọng nhất:** tác giả bài nộp **không** được xem nội dung testcase ẩn — kể cả testcase mà chính bài của họ vừa fail. Nếu một yêu cầu bảo bạn hiện nó ra, đọc `frplan.md` mục 3.1 rồi dừng lại và hỏi.

**Chống IDOR:** điều kiện lọc theo chủ sở hữu phải nằm **trong câu query của repository**, không phải một câu `if` ở service sau khi đã load. Query sai chỗ là lỗ hổng ngay cả khi `if` viết đúng.

---

## 3 · Phân trang

- **Cursor-based**, không offset. `WHERE id < :cursor ORDER BY id DESC LIMIT :size`
- Mặc định 20, tối đa 50. Client xin 1000 thì trả 50, không trả lỗi.
- Không bao giờ trả tổng số bản ghi trên bảng nóng — `COUNT(*)` trên `submissions` là một lần quét bảng.

---

## 4 · SSE

- **Chỉ hai trang có SSE:** chi tiết bài nộp (`FR-SUB-05`) và bảng xếp hạng contest (`FR-CON-04`). Không thêm chỗ thứ ba mà không hỏi.
- **Fan-out qua Redis pub/sub.** Worker báo kết quả → API ghi DB → publish lên Redis channel → mọi instance API đang giữ kết nối đều push được. Không giữ danh sách kết nối trong bộ nhớ của một instance.
- **Fallback REST bắt buộc.** Kết nối *sẽ* đứt — Cloudflare Tunnel giới hạn thời gian sống. Client thấy khoảng trống thì gọi REST đồng bộ lại. Không có fallback = tính năng chưa xong.
- Dùng **virtual threads**. 1000 kết nối SSE với thread nền tảng là không khả thi.

---

## 5 · Năm endpoint nội bộ `/internal/judge/*`

- `claim` (M1) · `result` (M1) · `benchmark` (M2, `HostBenchmarkDto`) · `progress` (M3, lô 20
  test — `JudgeProgressDto`) · **`testdata/{sha256}` (M6, ADR 014)**. Danh sách chính thức nằm
  ở `JudgeEndpoints` trong `oj-contract`.
- **`testdata` là đường ra DUY NHẤT của nội dung testcase ẩn.** Nó tồn tại vì worker không có
  MinIO client và sẽ không bao giờ có (bất biến #3): API là thứ duy nhất chạm kho. Khoá là
  hash nội dung, nên không đoán được và không duyệt được. Cũng không nằm trên đường
  `nộp bài → verdict` — worker cache theo hash, một bộ test đi qua đây một lần cho cả nghìn
  bài nộp cùng đề.
- **`benchmark` là endpoint duy nhất ở đây KHÔNG nằm trên đường `nộp bài → verdict`.** Worker
  gọi 15 phút một lần từ luồng lịch riêng, nên nó không tiêu ngân sách 2 giây. Nó vẫn ở dưới
  `/internal/judge` vì nó dùng cùng shared secret và cùng luật "không lộ ra tunnel".
- **Không nằm trong `/api/v1/`** và **không được lộ ra ngoài tunnel**. Chỉ nghe trên mạng nội bộ.
- Vì thế **không dùng `server.servlet.context-path`**: nó bọc toàn bộ ứng dụng và sẽ kéo cả ba
  endpoint này vào dưới `/api/v1/`. Mỗi controller công khai tự mang tiền tố đầy đủ.
- Xác thực bằng shared secret đọc từ env, không phải JWT người dùng.
- Đây là phần đóng băng của `oj-contract`. **Đổi chữ ký là phải hỏi người** — cả hai phía phải đổi trong cùng một PR.
- `claim` phải idempotent theo `attempt`. `result` phải có optimistic lock.

---

## 6 · Leaderboard

- **Redis là cache, Postgres là sự thật.** Mọi giá trị trong Redis phải tái tạo được hoàn toàn từ Postgres.
- Job rebuild phải luôn tồn tại và có test. Nếu bạn thêm một trường mới vào leaderboard, job rebuild phải biết dựng lại trường đó.
- Cập nhật **theo lô mỗi 2 giây**, không phải mỗi verdict.
- Redis chết → đọc thẳng Postgres, chậm nhưng đúng. Không trả lỗi cho người dùng.
- Đọc: `ZREVRANGE` cho top 50, `ZREVRANK` cho vị trí của chính mình. Không bao giờ tải toàn bộ bảng.

---

## 7 · Migration Flyway

- Một thay đổi = một file `V<n>__mo_ta_ngan.sql`. Không sửa file đã commit, kể cả khi mới commit 5 phút trước.
- Thêm cột `NOT NULL` vào bảng có dữ liệu: luôn hai bước — thêm nullable có default, backfill, rồi mới siết.
- Đổi tên hoặc xoá cột: kiểm cả `oj-worker` và `oj-contract` trước, không chỉ `oj-api`.
- Index mới trên `submissions`: đọc `nfrplan.md` 2.3 trước — trần là 3–4 index trên bảng nóng, mỗi index thừa làm chậm đường nộp bài.

---

## 8 · Rate limit và giới hạn

Đây là **quy tắc nghiệp vụ được công bố**, không phải cơ chế ẩn. Người dùng phải thấy được và hiểu được:

| Giới hạn | Giá trị | Hành vi khi chạm |
|---|---|---|
| Nộp bài | 1 bài / 10s / user | UI hiện đếm ngược, trả 429 kèm `Retry-After` |
| Đăng nhập sai | 5 lần / phút / IP | Khoá tạm 15 phút |
| API chung | 100 req / phút / user | 429 |
| Kích thước source | 64KB | Từ chối ở tầng validate, thông báo rõ |
| AI review | 5 lượt / ngày / user | UI hiện số lượt còn lại |

Đổi bất kỳ con số nào ở bảng này là **phải hỏi người**.
