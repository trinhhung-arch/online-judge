# Quy tắc sửa code — Online Judge

> Đọc file này trước mọi thay đổi. File này thắng mọi thói quen chung về "code sạch".
> Quy tắc theo vùng nằm ở `oj-api/CLAUDE.md` và `oj-worker/CLAUDE.md` — đọc thêm khi đụng vào vùng đó.

---

## 0 · Bối cảnh 30 giây

Hệ thống chấm bài lập trình tự động. Người lạ nộp mã nguồn, hệ thống **biên dịch và chạy mã đó trên máy chủ**, so sánh kết quả với đáp án, trả về verdict.

Ba thứ hệ thống này bán, xếp theo thứ tự không thể thoả hiệp:

1. **Tính công bằng** — cùng một bài phải cho cùng một kết quả, và không ai được biết thứ người khác không biết.
2. **Không mất bài nộp** — mất một bài giữa kỳ thi là lỗi không sửa được, không xin lỗi được.
3. **An toàn** — mã của người lạ chạy trên máy cá nhân của chủ dự án.

Mọi quy tắc dưới đây phục vụ ba điều này. Tốc độ và trải nghiệm đứng sau.

**Kiến trúc:** Java 21 · Spring Boot 4.1 · Postgres 16 (nguồn sự thật) · RabbitMQ (chỉ là đường dẫn) · Redis (cache + pub/sub) · `isolate` (sandbox) · modular monolith `oj-api` + worker rời `oj-worker` + `oj-contract` ở giữa.

**Tài liệu nguồn:** `docs/build-order.md` (thứ tự viết code) · `docs/nfrplan.md` (chỉ số chất lượng, mã P/S/SEC/R/U/A/M/C/AI) · `docs/frplan.md` (chức năng, mã FR-*) · `docs/postgres-design.md` (schema) · `docs/cau-truc-source.md` (file nào ở đâu).

> Mọi tham chiếu dạng `nfrplan.md` / `frplan.md` trong javadoc đều trỏ tới `docs/` — tên file đã được đặt khớp đúng chuỗi ấy để `grep` ra được ngay.

---

## 1 · Đọc gì trước khi làm gì

| Bạn sắp đụng vào | Đọc trước |
|---|---|
| Bất cứ thứ gì | File này, mục 2 và 3 |
| `oj-api/**` | `oj-api/CLAUDE.md` |
| `oj-worker/**` | `oj-worker/CLAUDE.md` |
| Hành vi người dùng nhìn thấy | `docs/frplan.md` Phần 2 — tìm mã FR tương ứng |
| Bất cứ thứ gì có con số (timeout, giới hạn, quota) | `docs/nfrplan.md` Phần 1 — bảng SLO |
| Hàng đợi, reaper, ghi verdict | `docs/nfrplan.md` Phần 5 · `docs/sql/duong_nong.sql` truy vấn 2–4 |
| Sandbox, chạy mã người dùng | `docs/nfrplan.md` Phần 4.1 |

---

## 2 · Mười hai điều tuyệt đối không

Đây không phải khuyến nghị. Vi phạm bất kỳ điều nào là lỗi nghiêm trọng, kể cả khi code chạy được và test xanh.

| # | Không bao giờ | Vì sao | Mã |
|---|---|---|---|
| 1 | **Để nội dung testcase ẩn rời khỏi worker** — không qua API response, log, exception message, prompt LLM, hay bất cứ đâu | Người dùng rút trích được toàn bộ bộ test bằng cách nộp bài cố tình sai từng test một, rồi nộp bảng tra cứu đáp án | SEC3 |
| 2 | **Để `SubmitSolution` chờ verdict** — không gọi worker đồng bộ, không `.get()`, không `.join()`, không `@Transactional` bao quanh việc publish | Worker chậm là cả site đơ; 500 người nộp cùng lúc là 500 connection treo | P2, R1 |
| 3 | **Cho `oj-worker` một `DataSource`** — worker chỉ biết các đường dẫn liệt kê trong `JudgeEndpoints` (`claim` · `result` · `progress` · `benchmark`) | Worker có DB là worker không scale ngang được và không đổi transport được | S1, S2 |
| 4 | **Chạy mã người dùng ngoài `isolate`** — kể cả bước biên dịch, kể cả "chỉ để thử nhanh" | Compiler bomb và fork bomb là có thật; `ProcessBuilder` + timeout không phải sandbox | SEC1 |
| 5 | **Nối chuỗi vào SQL** — chỉ `JdbcClient` với named parameter | ArchUnit chặn, và đây là lỗ hổng kinh điển | SEC2 |
| 6 | **Sửa file Flyway đã commit** — luôn tạo `V<n+1>__mo_ta.sql` mới | Hai máy dev lệch schema, và không ai biết cho đến khi lỗi lạ xuất hiện | M |
| 7 | **Ghi verdict mà không có optimistic lock.** Câu lệnh đầu tiên của transaction ghi verdict phải là `DELETE FROM judge_queue WHERE submission_id=? AND attempt=?`; 0 dòng → bỏ qua im lặng | RabbitMQ là at-least-once — job *sẽ* được giao hai lần | R2 |
| 8 | **Trả về danh sách không có `LIMIT`** — mọi endpoint danh sách phải phân trang | `submissions` sẽ có hàng triệu dòng | S3, P1 |
| 9 | **Log source người dùng, mật khẩu, token, nội dung testcase, hay prompt/response LLM** | Rò rỉ qua log là đường rò rỉ dễ quên nhất | SEC3 |
| 10 | **Gọi LLM từ ngoài package `ai`, hoặc đặt LLM trên đường verdict** | Một lần LLM chậm là cả hệ thống chấm bài đứng | AI1 |
| 11 | **Kiểm quyền chỉ ở controller** — use-case phải tự kiểm | Một request API trực tiếp bỏ qua UI là chuyện 5 phút | SEC |
| 12 | **`System.out.println`** — dùng logger có `traceId` | Không truy được sự cố xuyên API → queue → worker | M |

> Nếu nhiệm vụ được giao **buộc** bạn vi phạm một trong 12 điều trên: **dừng lại và nói ra**. Không im lặng làm rồi ghi chú nhỏ ở cuối.

> **Ghi chú về #7 — chỗ đặt khoá lạc quan đã đổi, ngữ nghĩa thì không.** Bản đầu của file này
> viết `WHERE id=? AND attempt=? AND status='JUDGING'` **trên `submissions`**. Đo thật cho thấy
> muốn câu đó nhanh thì phải index `status` và `attempt`, mà index chúng là mất HOT update trên
> bảng nóng: **100% → 0%** (`docs/postgres-design.md` mục 4). Khoá được chuyển sang `judge_queue`
> — bảng vài trăm dòng — với cùng một bảo đảm. Xem `docs/adr/009-khoa-lac-quan-tren-judge-queue.md`.

---

## 3 · Chiều phụ thuộc — ArchUnit ép, vi phạm là fail CI

```
Giữa các module (một chiều, không có ngoại lệ):

    identity ──▶ problems ──▶ judging ──▶ contests
                                 ▲
                              ai (AI review)

    platform (config, error, security) ◀── ai cũng import được

Trong mỗi module (một chiều):

    api ──▶ application ──▶ domain
                 │
                 └──▶ infrastructure
```

**Bốn luật cứng:**

1. `domain` không import Spring, JPA, Jackson, hay bất kỳ framework nào. Java thuần.
2. Module X không import `infrastructure` của module Y. Chỉ import package public của Y.
3. Không có chiều ngược. `problems` không biết `judging` tồn tại.
4. `oj-contract` không import gì ngoài JDK. Nó là biên giới giữa hai người và hai tiến trình.

---

## 4 · Sáu câu hỏi trước khi viết dòng đầu tiên

Trả lời trong đầu, mất 2 phút, bắt được hầu hết lỗi thiết kế:

1. **Thay đổi này có làm dữ liệu nào lộ ra không?** Testcase ẩn · source người khác · đề chưa mở · bài nộp trong contest đang chạy.
2. **Có trả về danh sách không?** → `LIMIT` + phân trang cursor-based, ghi giới hạn vào chính đặc tả.
3. **Chạy hai lần thì sao?** Nếu chưa trả lời được thì chưa xong thiết kế.
4. **Có thể vượt 5 giây không?** → job nền có `jobId` và tiến độ, không phải một request. Và nó cạnh tranh tài nguyên với bài nộp trực tiếp thế nào?
5. **Có chạm vào contest đang diễn ra không?** → mặc định **cấm**, rồi mới bàn ngoại lệ.
6. **Có thêm một chặng vào đường `nộp bài → verdict` không?** → ngân sách tổng là 2 giây (`nfrplan.md` 2.1). Chặng mới lấy từ đâu ra?

---

## 5 · Khi nào PHẢI dừng và hỏi người

Tám tình huống. Trong tám tình huống này, **không tự quyết**:

1. Cần đổi bất cứ thứ gì trong `oj-contract`, hoặc bất kỳ endpoint nào trong `JudgeEndpoints` — đây là hợp đồng đã đóng băng giữa hai người.
2. Cần thêm dependency mới vào `pom.xml`.
3. Cần sửa một file migration đã commit.
4. Cần đổi một con số đã chốt: số judge slot · timeout reaper 120s · rate limit 10s · quota AI 5/ngày · giới hạn ZIP 200MB · source 64KB.
5. Nhiệm vụ yêu cầu một thứ nằm trong danh sách "KHÔNG làm" (`nfrplan.md` Phần 12, `frplan.md` Phần 5).
6. Thay đổi chạm cả `oj-api` lẫn `oj-worker` trong một lần.
7. Phải đánh đổi một SLO để đạt SLO khác.
8. Không tìm thấy test hiện có nào bao phủ vùng sắp sửa — nghĩa là bạn đang sửa mù.

**Cách dừng cho đúng:** nêu chính xác điều gì chặn · đưa 2–3 phương án · khuyến nghị một cái kèm lý do · **rồi chờ**. Không viết code "tạm" trong lúc chờ.

---

## 6 · Test bắt buộc theo loại thay đổi

Không có test tương ứng thì thay đổi chưa xong, dù code có đúng.

| Loại thay đổi | Test bắt buộc |
|---|---|
| Use-case mới hoặc sửa | Unit test với fake repository + fake queue |
| Repository | Testcontainers — Postgres thật, không H2 |
| Endpoint mới | Test phân quyền: gọi bằng vai trò sai phải trả 403, **không phải 200 rỗng** |
| Đụng vào sandbox | Chạy lại **toàn bộ 14 test tấn công** (`nfrplan.md` 4.1) |
| Đụng vào queue, reaper, hoặc ghi verdict | Chaos test: kill giữa chừng, kiểm 0 mất 0 trùng |
| Đụng vào AI review | 8 test prompt injection (`nfrplan.md` 10.2) |
| Query mới trên bảng nóng | Đếm số query (chống N+1) + đọc `EXPLAIN` |
| Migration | Chạy được trên DB rỗng **và** DB đã có dữ liệu |
| Đụng vào bảng `languages` hoặc hệ số thời gian | Smoke test cả 3 ngôn ngữ trên host chuẩn |

---

## 7 · Quy ước

- **Package:** `dev.oj.<module>.{domain,application,infrastructure,api}`
- **Entity không rời `domain`.** DTO định nghĩa ở `api`. Không bao giờ trả entity trực tiếp ra HTTP.
- **Exception:** mỗi module có exception riêng của mình. Không ném `RuntimeException` trần.
- **Config:** mọi ngưỡng, giới hạn, timeout đều là thuộc tính có tên trong `application.yml`. Không có số ma thuật rải trong code.
- **Kích thước:** file ≤ 300 dòng, method ≤ 50 dòng. Vượt thì tách, đừng xin ngoại lệ.
- **Đổi tên file:** luôn `git mv`. Không đổi trong IDE — macOS và Windows không phân biệt hoa thường, Linux thì có, và file sẽ "biến mất" của người kia.
- **Nhánh:** `a/<viec>` hoặc `b/<viec>`. Sống ≤ 3 ngày.
- **API:** mọi endpoint công khai dưới `/api/v1/`.

---

## 8 · Định nghĩa "xong"

- [ ] `./mvnw verify` xanh trên máy
- [ ] Test theo bảng mục 6 đã thêm
- [ ] Không vi phạm điều nào ở mục 2 — hoặc đã nói ra nếu buộc phải vi phạm
- [ ] Ngưỡng mới nằm trong config, không hardcode
- [ ] Hành vi người dùng thay đổi → mã FR tương ứng trong `frplan.md` đã cập nhật
- [ ] Quyết định kiến trúc thay đổi → đã thêm file ADR trong `docs/adr/`

---

## 9 · Báo cáo bắt buộc sau mỗi thay đổi

Kết thúc mọi thay đổi bằng đúng khối này. Ngắn, không diễn giải:

```
File đã sửa:      <danh sách>
Bất biến bị chạm: <số hiệu ở mục 2, hoặc "không">
SLO có thể ảnh hưởng: <mã trong nfrplan Phần 1, hoặc "không">
FR liên quan:     <mã FR, hoặc "không">
Test đã thêm:     <tên test>
Cần người quyết:  <không | mô tả ngắn>
```

Nếu ô **"Bất biến bị chạm"** khác "không", giải thích ngay bên dưới vì sao và bạn đã bù bằng gì.

---

## 10 · Từ vựng — dùng đúng từ, đừng tự đặt tên mới

| Từ | Nghĩa trong dự án này |
|---|---|
| **submission** | Một lần nộp bài. Không gọi là "solution" hay "answer" |
| **verdict** | Kết quả chấm: `AC` `WA` `TLE` `MLE` `RE` `CE` `IE` |
| **attempt** | Lần chấm thứ mấy của cùng một submission. Tăng khi reaper nhặt lại hoặc rejudge |
| **testcase** | Một cặp input/output. Có cờ `sample` (công khai) hoặc ẩn |
| **checker** | Bộ so sánh output: `exact`, `token`, `float epsilon` |
| **subtask** | Nhóm testcase có điểm riêng |
| **box** | Một sandbox `isolate` đang chạy. Mỗi judge slot một box |
| **reaper** | Job nền đưa submission kẹt ở `JUDGING` quá 120s về `QUEUED` |
| **host_factor** | Hệ số hiệu chuẩn tốc độ máy chấm. Nhân vào giới hạn thời gian |
| **feedback_level** | Mức phản hồi của một đề: `NONE` / `TEST_INDEX` / `SAMPLE_DETAIL` |
| **judge slot** | Một luồng chấm song song. Số slot cố định theo cấu hình host |

---

## Một câu để nhớ

**Phần lớn tính năng nghe hợp lý nhất trong một Online Judge lại chính là tính năng phá hoại nó** — vì thứ hệ thống này bán là sự công bằng, mà công bằng bị phá bởi những thứ trông rất giống lòng tốt.

Khi một thay đổi có vẻ "rõ ràng là cải thiện trải nghiệm", hãy hỏi lại nó lộ ra cái gì.
