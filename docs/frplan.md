# Đặc tả yêu cầu chức năng (FR) — Online Judge v1.0
### Viết sao cho không mâu thuẫn với NFR

**Bộ ba tài liệu:** `build-order.md` (thứ tự viết code) · `nfrplan.md` (chất lượng) · **tài liệu này** (chức năng)
**Nguyên tắc chi phối:** mỗi FR phải khai báo rõ nó bị NFR nào ràng buộc. FR nào không sống được cùng NFR thì **sửa cách viết hoặc loại bỏ ngay tại bàn giấy**, không để phát hiện ở tuần 11.

---

## PHẦN 0 — Vì sao FR và NFR mâu thuẫn nhau, và mâu thuẫn ở đâu

Mâu thuẫn FR↔NFR gần như không bao giờ đến từ việc ai đó viết sai. Nó đến từ việc **FR được viết bằng ngôn ngữ của người dùng, còn NFR được viết bằng ngôn ngữ của hệ thống** — và hai bên không đọc của nhau.

Ví dụ kinh điển của chính dự án này:

> **FR viết theo bản năng:** *"Người dùng nộp bài và hệ thống trả về kết quả chấm."*
> **NFR bị phá:** toàn bộ kiến trúc `accept ≠ process` — thứ mà plan gốc coi là quyết định quan trọng nhất của M1.

Câu FR trên nghe hoàn toàn vô hại. Nhưng chữ "**trả về**" ép người lập trình viết một request đồng bộ: nộp bài → chờ worker chấm → trả verdict. Hệ quả dây chuyền: worker chậm thì cả site đơ · không thể tắt worker để bảo trì · 500 người nộp cùng lúc là 500 HTTP connection treo · SLO P2 (submit < 300ms) không thể đạt · R1 (không mất bài) sụp đổ vì bài chỉ tồn tại trong bộ nhớ của request đang chờ.

Một chữ trong FR phá năm chỉ số NFR. Đó là lý do tài liệu này tồn tại.

**Cách viết đúng:**

> **FR-SUB-02:** *Hệ thống ghi nhận bài nộp và trả về `submissionId` cùng trạng thái `QUEUED` trong ≤ 300ms. Kết quả chấm được cập nhật sau đó và đẩy tới người dùng qua kênh realtime.*

Cùng một nhu cầu của người dùng, nhưng bây giờ FR **mô tả hành vi quan sát được** thay vì ngầm quy định cách cài đặt.

---

## PHẦN 1 — Năm quy tắc viết FR để không phá NFR

Đây là phần quan trọng nhất tài liệu. Mọi FR mới trong tương lai phải qua được 5 câu hỏi này.

### Quy tắc 1 — FR mô tả hành vi quan sát được, không mô tả cách làm

| ❌ Viết sai | ✅ Viết đúng | NFR được cứu |
|---|---|---|
| "Hệ thống gọi worker chấm rồi trả kết quả" | "Hệ thống nhận bài, trả `submissionId` ≤300ms; kết quả cập nhật sau" | P2, R1, S1 |
| "Hệ thống lưu leaderboard vào Redis" | "Bảng xếp hạng cập nhật trong ≤2s sau khi có verdict mới" | P8, A (Redis chết vẫn chạy) |
| "Worker hỏi server xem có bài nào không mỗi 500ms" | "Bài nộp được nhận chấm trong ≤100ms kể từ khi vào hàng đợi" | P3 |

> Dấu hiệu nhận biết FR viết sai: nó chứa tên công nghệ, hoặc chứa động từ mô tả việc hệ thống làm với chính nó. FR chỉ được nói về những gì **người dùng nhìn thấy**.

### Quy tắc 2 — Không có FR nào được phép "xem tất cả"

Mọi FR trả về danh sách **bắt buộc** có phân trang, và phải ghi rõ giới hạn ngay trong FR.

- ❌ "Người dùng xem lịch sử các bài nộp của mình"
- ✅ "Người dùng xem lịch sử bài nộp của mình, **phân trang cursor-based, tối đa 50 bản ghi/trang**, lọc được theo đề / verdict / ngôn ngữ"

Cứu **S3** (`submissions` 1M+ dòng không degrade) và **P1** (API p95 < 200ms). Một trang "xem tất cả" trên bảng 1 triệu dòng là một câu query không có `LIMIT` — và nó sẽ tồn tại mãi mãi trong code nếu FR cho phép nó ra đời.

### Quy tắc 3 — Mọi FR hiển thị dữ liệu phải khai báo *ai xem được* và *khi nào*

Đây là quy tắc chống rò rỉ testdata (SEC3) và chống gian lận trong contest. Ma trận dưới đây là **một phần của đặc tả**, không phải phụ lục:

| Dữ liệu | GUEST | USER | Tác giả bài nộp | SETTER (đề của mình) | ADMIN |
|---|---|---|---|---|---|
| Đề đã xuất bản | ✅ | ✅ | ✅ | ✅ | ✅ |
| Đề trong contest chưa mở | ❌ | ❌ | ❌ | ✅ | ✅ |
| Testcase **sample** (public) | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Nội dung testcase ẩn** | ❌ | ❌ | **❌** | ✅ | ✅ |
| Verdict bài nộp của mình | — | — | ✅ | ✅ | ✅ |
| **Số thứ tự test bị fail** | — | — | tuỳ `feedback_level` | ✅ | ✅ |
| Log compiler (CE) | — | — | ✅ | ✅ | ✅ |
| Source người khác — ngoài contest | ❌ | tuỳ cấu hình đề | — | ✅ | ✅ |
| Source người khác — **trong contest** | ❌ | **❌** | — | **❌** | ✅ |
| Bảng xếp hạng chưa freeze | ✅ | ✅ | ✅ | ✅ | ✅ |
| Bảng xếp hạng đã freeze | ❄️ | ❄️ | ❄️ | ❄️ | ✅ đầy đủ |
| AI review | — | — | ✅ (ngoài contest) | ❌ | ✅ |
| `audit_log` | ❌ | ❌ | ❌ | ❌ | ✅ |

> ⚠️ Ô quan trọng nhất bảng này: **tác giả bài nộp KHÔNG được xem nội dung testcase ẩn** — kể cả testcase mà chính bài của họ vừa fail. Lý do ở mục 3.1.

### Quy tắc 4 — Mọi FR ghi dữ liệu phải khai báo hệ quả khi bị lặp lại

Hệ thống dùng RabbitMQ at-least-once, có reaper, có retry. Nghĩa là **mọi thao tác ghi chắc chắn sẽ có lúc chạy hai lần**. FR phải nói rõ lần thứ hai xảy ra chuyện gì.

| FR | Lặp lại lần 2 thì sao |
|---|---|
| Ghi kết quả chấm | Bị từ chối bởi optimistic lock — verdict cũ giữ nguyên |
| Nộp bài (double-click) | Tạo 2 bài nộp riêng biệt, nhưng rate limit chặn bài thứ 2 |
| Gọi AI review cùng submission | Trả review đã lưu, **không gọi LLM lần nữa, không trừ quota** |
| Rejudge một submission | Tạo `attempt` mới, verdict cũ vẫn lưu trong lịch sử |

Cứu **R2** (0 bài bị chấm 2 lần) và **AI2** (chi phí LLM).

### Quy tắc 5 — Mọi FR chạy lâu là job nền có tiến độ, không phải một request

Ngưỡng: **bất cứ thao tác nào có thể vượt 5 giây**. Ba cái trong hệ thống này:

- Rejudge hàng loạt (có thể là 10.000 bài)
- Upload và validate ZIP đề bài (có thể 200MB)
- Rebuild leaderboard từ Postgres

Cả ba đều phải: trả về `jobId` ngay · có endpoint xem tiến độ · **chạy được tiếp sau khi restart** · và với rejudge thì **ưu tiên thấp hơn bài nộp trực tiếp** (xem mâu thuẫn #2).

---

## PHẦN 2 — Danh sách FR theo module

Ký hiệu cột **Ràng buộc NFR**: mã chỉ số trong `nfrplan.md` Phần 1. Cột **Mốc** khớp với plan gốc.

### 2.1 — FR-AUTH · Danh tính và phân quyền  *(M4, tuần 7 — Người A)*

| ID | Yêu cầu | Ràng buộc NFR | Ưu tiên |
|---|---|---|---|
| FR-AUTH-01 | Đăng ký bằng email + mật khẩu. Mật khẩu ≥ 8 ký tự, băm BCrypt cost 12 | SEC2 | Must |
| FR-AUTH-02 | Đăng nhập trả JWT (15 phút) + refresh token (7 ngày). **Không dùng session in-memory** | S2, S1 | Must |
| FR-AUTH-03 | Đăng xuất — thu hồi refresh token | — | Must |
| FR-AUTH-04 | Đổi mật khẩu (yêu cầu mật khẩu cũ), thu hồi mọi refresh token | SEC2 | Must |
| FR-AUTH-05 | Xem và sửa hồ sơ: tên hiển thị, ngôn ngữ ưa dùng | — | Must |
| FR-AUTH-06 | Ba vai trò USER / SETTER / ADMIN. **Kiểm quyền ở tầng use-case** | SEC2, M-ArchUnit | Must |
| FR-AUTH-07 | ADMIN vô hiệu hoá tài khoản — **không xoá cứng**, dữ liệu bài nộp giữ nguyên | R1, audit | Must |
| FR-AUTH-08 | Giới hạn 5 lần đăng nhập sai/phút/IP, khoá tạm 15 phút | SEC2 | Must |
| FR-AUTH-09 | Quên mật khẩu qua email | — | **Won't (v1.1)** — cần SMTP, thêm một điểm hỏng, không đáng cho v1.0 |

> **Quyết định FR-AUTH-02 là NFR trá hình.** "Đăng nhập" nghe như FR thuần, nhưng chọn JWT hay session quyết định API có scale ngang được không (S1, S2). Đây là lý do FR và NFR phải viết cùng nhau chứ không phải hai người viết hai lần.

### 2.2 — FR-PROB · Quản lý đề bài  *(M1 cơ bản, M4 đầy đủ — A và B)*

| ID | Yêu cầu | Ràng buộc NFR | Ưu tiên |
|---|---|---|---|
| FR-PROB-01 | SETTER tạo đề: mã đề, tiêu đề, mô tả Markdown + LaTeX, giới hạn thời gian, giới hạn bộ nhớ | — | Must |
| FR-PROB-02 | Mô tả đề render server-side, **cache theo hash nội dung** | P1 | Must |
| FR-PROB-03 | Upload testdata qua ZIP (`problem.yaml` + `tests/`). Giới hạn: **≤ 200MB, ≤ 1000 test, tỉ lệ nén ≤ 100:1** | SEC2 (zip bomb), R (disk) | Must |
| FR-PROB-04 | Đánh dấu từng testcase là **sample** (công khai) hoặc **hidden** | SEC3 | Must |
| FR-PROB-05 | Chọn checker: `exact` · `token` · `float epsilon` | — | Must |
| FR-PROB-06 | Chia subtask/batch, điểm theo nhóm, phụ thuộc giữa nhóm | — | Should |
| FR-PROB-07 | **Đặt `feedback_level` cho đề**: `NONE` / `TEST_INDEX` / `SAMPLE_DETAIL` — xem mâu thuẫn #1 | **SEC3** | **Must** |
| FR-PROB-08 | Xuất bản / gỡ đề. Đề chưa xuất bản chỉ tác giả và ADMIN thấy | SEC (Quy tắc 3) | Must |
| FR-PROB-09 | Danh sách đề: lọc theo tag / độ khó / đã giải, **phân trang 50/trang** | P1, S3 | Must |
| FR-PROB-10 | **Sửa testdata khi đã có bài nộp** → hệ thống cảnh báo, bắt buộc tạo job rejudge, ghi `audit_log` | R (toàn vẹn), Quy tắc 5 | Must |
| FR-PROB-11 | **Cấm sửa đề đang nằm trong contest đang diễn ra** | Tính công bằng | Must |
| FR-PROB-12 | Tải về testdata — chỉ SETTER của đề đó và ADMIN | SEC3 | Should |

### 2.3 — FR-SUB · Nộp bài và chấm bài  *(M1 — lõi của toàn dự án)*

| ID | Yêu cầu | Ràng buộc NFR | Ưu tiên |
|---|---|---|---|
| FR-SUB-01 | Nộp bài: chọn ngôn ngữ, dán hoặc upload source **≤ 64KB** | AI2 (giới hạn prompt), SEC | Must |
| FR-SUB-02 | **Hệ thống trả `submissionId` + trạng thái `QUEUED` trong ≤300ms. Verdict đến sau.** | **P2, R1, S1** | **Must** |
| FR-SUB-03 | Vòng trạng thái `QUEUED → JUDGING → DONE`. Bài kẹt ở `JUDGING` quá 120s tự về `QUEUED` | R1, R3 | Must |
| FR-SUB-04 | Bảy verdict: `AC` `WA` `TLE` `MLE` `RE` `CE` `IE` | — | Must |
| FR-SUB-05 | Theo dõi tiến độ realtime qua SSE, **có fallback REST polling khi mất kết nối** | U, A (tunnel ngắt) | Must |
| FR-SUB-06 | Trang chi tiết bài nộp hiển thị **đủ lý do cho cả 7 verdict** (bảng 6.2 nfrplan) | U3 | Must |
| FR-SUB-07 | Lịch sử bài nộp của mình: **cursor-based, ≤50/trang**, lọc theo đề/verdict/ngôn ngữ | P1, S3 | Must |
| FR-SUB-08 | **Rate limit 1 bài/10s/user** — là quy tắc nghiệp vụ, hiển thị đếm ngược trên UI | SEC2, P4 | Must |
| FR-SUB-09 | **Không ai được xoá bài nộp.** ADMIN chỉ ẩn được, có ghi `audit_log` | R1, audit | Must |
| FR-SUB-10 | Giữ nháp code trong trình duyệt, khôi phục khi mở lại | U | Should |
| FR-SUB-11 | Hiển thị thời gian chạy **làm tròn 10ms**, kèm chú thích máy chấm chuẩn | P7, C (ARM/x86) | Must |
| FR-SUB-12 | Bài `IE` được tự động chấm lại tối đa 2 lần trước khi báo lỗi cho người dùng | R3 | Must |

### 2.4 — FR-CON · Kỳ thi  *(M5, tuần 10–12 — Người A)*

| ID | Yêu cầu | Ràng buộc NFR | Ưu tiên |
|---|---|---|---|
| FR-CON-01 | Tạo contest: tên, thời gian bắt đầu/kết thúc, thể thức (`ICPC`/`IOI`), danh sách đề | M4-nfr (thêm thể thức = 1 file) | Must |
| FR-CON-02 | Đăng ký tham gia trước giờ bắt đầu | — | Must |
| FR-CON-03 | **Đề của contest chỉ truy cập được trong khung giờ** — kiểm ở tầng use-case, không phải ẩn nút | SEC (Quy tắc 3) | Must |
| FR-CON-04 | Bảng xếp hạng cập nhật **≤2s** sau verdict mới. Hiển thị **top 50 + vùng quanh mình**, không tải toàn bộ | **P8, P1** | Must |
| FR-CON-05 | Đóng băng bảng xếp hạng N phút cuối; ADMIN vẫn xem được bản đầy đủ | — | Must |
| FR-CON-06 | `ICPCFormat`: penalty time · `IOIFormat`: điểm subtask, lấy lần cao nhất | — | Must |
| FR-CON-07 | Sau khi contest kết thúc: mở đề ra ngoài, **mở lại AI review**, công bố bảng xếp hạng đầy đủ | AI (công bằng) | Must |
| FR-CON-08 | ADMIN chạy job **rebuild bảng xếp hạng từ Postgres** — job nền có tiến độ | Quy tắc 5, A (Redis chết) | Must |
| FR-CON-09 | Job đối soát dữ liệu denormalize + metric `drift` + alert | R | Must |
| FR-CON-10 | Virtual participation | — | **Should** — ứng viên đầu tiên bị cắt nếu thiếu thời gian |

### 2.5 — FR-AI · AI Code Reviewer  *(tuần 14–15 nếu chọn phương án C)*

| ID | Yêu cầu | Ràng buộc NFR | Ưu tiên |
|---|---|---|---|
| FR-AI-01 | Nút "Nhận góp ý AI" trên trang chi tiết bài nộp — **do người dùng bấm, không tự động** | **AI2** | Must |
| FR-AI-02 | **Chỉ khả dụng ngoài thời gian contest.** Kiểm ở tầng use-case theo `contest.status` | Tính công bằng | Must |
| FR-AI-03 | Quota 5 review/ngày/user, UI hiển thị số lượt còn lại | AI2 | Must |
| FR-AI-04 | Review trả về dạng Markdown, hiển thị dần qua SSE | U, AI1 | Should |
| FR-AI-05 | **Nội dung review: nhận xét độ phức tạp, phong cách, gợi ý hướng sửa. KHÔNG đưa mã giải hoàn chỉnh** | Giá trị sư phạm | Must |
| FR-AI-06 | Review được lưu; xem lại **không gọi LLM, không trừ quota** | AI2, Quy tắc 4 | Must |
| FR-AI-07 | Nút 👍/👎 cho mỗi review | M (cải thiện prompt) | Should |
| FR-AI-08 | Khi LLM lỗi: hiện "tạm không khả dụng", **không trừ quota, verdict không ảnh hưởng** | AI1, A | Must |
| FR-AI-09 | ADMIN có **kill switch** tắt toàn bộ AI review tức thì | AI2, A | Must |

### 2.6 — FR-ADM · Quản trị và vận hành  *(M6, tuần 11–12)*

| ID | Yêu cầu | Ràng buộc NFR | Ưu tiên |
|---|---|---|---|
| FR-ADM-01 | **Rejudge hàng loạt** — job nền, có tiến độ, dừng/tiếp được, **ưu tiên thấp hơn bài nộp trực tiếp** | **P4, P6**, Quy tắc 5 | Must |
| FR-ADM-02 | Xem `audit_log`, lọc theo người thực hiện / hành động / thời gian, phân trang | SEC, S3 | Must |
| FR-ADM-03 | Quản lý người dùng: đổi vai trò, vô hiệu hoá | SEC | Must |
| FR-ADM-04 | Dashboard vận hành: độ dài hàng đợi · thời gian chờ · số worker sống · tỉ lệ IE · drift · chi phí LLM | P, R3, AI2 | Must |
| FR-ADM-05 | **Trang trạng thái công khai**: "hiện có N bài đang chờ, thời gian chờ ước tính Xs" | U, A | Should |
| FR-ADM-06 | Bật/tắt nhận bài nộp mới (chế độ bảo trì) — bài đang chấm vẫn chấm xong | A, R1 | Must |

---

## PHẦN 3 — Mười hai mâu thuẫn FR ↔ NFR đã phát hiện và cách giải quyết

Đây là phần bạn nên đọc kỹ nhất. Mỗi mục có cấu trúc: *cách viết bản năng → NFR bị phá → cách viết lại*.

### 3.1 — Hiển thị test bị sai ↔ SEC3 (không rò rỉ testdata) ⚠️ NGHIÊM TRỌNG NHẤT

**FR bản năng:** *"Khi bài sai, hiển thị input, expected output và actual output của test bị sai để người dùng biết sửa."*

**NFR bị phá:** SEC3 — 0 đường rò rỉ testdata.

**Vì sao:** đây không phải rủi ro lý thuyết mà là **một thuật toán rút trích**. Người dùng nộp một chương trình cố tình in sai ở test 1 → nhận được nội dung test 1. Nộp tiếp chương trình đúng ở test 1, sai ở test 2 → nhận test 2. Lặp lại N lần là có **toàn bộ bộ test**. Sau đó nộp một chương trình chỉ chứa bảng tra cứu đáp án — AC tuyệt đối mọi bài.

**Cách viết lại — FR-PROB-07, ba mức phản hồi cấu hình theo từng đề:**

| Mức | Người dùng thấy | Dùng khi |
|---|---|---|
| `NONE` | Chỉ verdict (`WA`), không nói test nào | Contest thể thức ICPC |
| `TEST_INDEX` | "Sai ở test 7/50" — **chỉ số thứ tự, không có nội dung** | Mặc định cho luyện tập |
| `SAMPLE_DETAIL` | Đầy đủ input/expected/actual **nhưng chỉ với testcase đã đánh dấu sample** | Bài dành cho người mới |

> Quy tắc tuyệt đối: **nội dung testcase ẩn không bao giờ rời khỏi worker.** Không qua API, không qua log, không qua thông báo lỗi, không qua prompt LLM.
>
> Điều này sửa lại dòng "WA" trong bảng 6.2 của `nfrplan.md` — bản đó viết *"(nếu đề cho phép) input/expected/actual"* còn quá lỏng. `feedback_level` là cách nói chính xác của cùng ý đó.

### 3.2 — Rejudge hàng loạt ↔ P4/P6 (throughput và thời gian chờ)

**FR bản năng:** *"ADMIN chấm lại toàn bộ bài nộp của một đề."*

**NFR bị phá:** P6 (p95 chờ < 5s) và trong trường hợp xấu là R1.

**Vì sao:** một đề phổ biến có 10.000 bài nộp. Đẩy hết vào cùng hàng đợi với bài nộp trực tiếp → với throughput 5 bài/s, hàng đợi tắc **33 phút**. Người dùng nộp bài trong khoảng đó chờ nửa tiếng. Nếu đúng lúc contest thì hỏng contest.

**Cách viết lại — FR-ADM-01:**
- **Hai hàng đợi riêng**: `judge.live` (ưu tiên cao) và `judge.rejudge` (ưu tiên thấp). Worker luôn hút cạn `live` trước.
- Rejudge **giới hạn tốc độ**: tối đa 30% năng lực chấm, tự động giảm về 0 khi `queue_wait` của live vượt 5s.
- Job có tiến độ, dừng/tiếp được, **sống sót qua restart**.
- **Cấm chạy rejudge khi có contest đang diễn ra** — kiểm ở tầng use-case.
- Verdict cũ **không bị ghi đè mà lưu thành `attempt` mới**, để đối chiếu khi rejudge cho kết quả khác.

### 3.3 — "Kết quả chấm trả về ngay" ↔ P2 + accept≠process

Đã phân tích ở Phần 0. **FR-SUB-02** là cách viết đúng. Nhắc lại ở đây vì đây là mâu thuẫn có sức phá hoại lớn nhất và cũng dễ tái phạm nhất — bất cứ ai viết lại FR mà không đọc tài liệu này đều sẽ viết "trả về kết quả".

### 3.4 — Bảng xếp hạng realtime ↔ P1 (API < 200ms) với 1000 thí sinh

**FR bản năng:** *"Bảng xếp hạng hiển thị thứ hạng của tất cả thí sinh, cập nhật realtime."*

**NFR bị phá:** P1 và P8. Một contest 1000 người × 10 đề = 10.000 ô điểm. Render toàn bộ mỗi lần có verdict mới, cho 1000 người đang mở trang, là ~10 triệu ô/giây.

**Cách viết lại — FR-CON-04:**
- Hiển thị **top 50 + 5 dòng quanh vị trí của chính mình** (Redis `ZRANGE` + `ZRANK`, cả hai O(log n))
- Cập nhật **theo lô mỗi 2 giây**, không phải mỗi verdict — đúng bằng SLO P8
- Phân trang khi muốn xem sâu hơn
- Đóng băng theo FR-CON-05 làm giảm tải hẳn ở giai đoạn cao điểm nhất

### 3.5 — Xoá tài khoản ↔ R1 (không mất dữ liệu) và tính toàn vẹn bảng xếp hạng

**FR bản năng:** *"Người dùng có quyền xoá tài khoản của mình."*

**NFR bị phá:** R1, `audit_log` append-only, và tính đúng đắn của mọi bảng xếp hạng lịch sử — xoá cứng một người từng đứng hạng 3 làm sai vĩnh viễn bảng xếp hạng contest đó.

**Cách viết lại — FR-AUTH-07:** **ẩn danh hoá, không xoá.** Tên hiển thị → `[đã xoá #1234]`, email và mật khẩu bị xoá thật, bài nộp và thứ hạng giữ nguyên. Ghi vào `audit_log`. Đây cũng là cách các OJ lớn làm.

### 3.6 — Hiển thị thời gian chạy chính xác ↔ P7 (độ lệch <5%) và C (ARM vs x86)

**FR bản năng:** *"Hiển thị thời gian chạy của bài nộp, ví dụ 0.023s."*

**NFR bị phá:** P7. Độ lệch đo lường là ±5% — nghĩa là chữ số hàng mili giây **là nhiễu, không phải thông tin**. Hiển thị nó tạo ra một trò chơi giả: người dùng nộp lại 10 lần để "tối ưu" từ 23ms xuống 21ms, trong khi thực tế họ chỉ đang lấy mẫu ngẫu nhiên. Tệ hơn: tốn 10 lượt chấm cho 0 giá trị.

**Cách viết lại — FR-SUB-11:** làm tròn đến **10ms**, kèm chú thích *"đo trên máy chấm chuẩn, sai số ±5%"*. Bảng xếp hạng theo tốc độ (nếu có) chỉ xếp theo bậc, không theo giá trị tuyệt đối.

### 3.7 — AI review "cho mọi bài nộp" ↔ AI2 (chi phí) và tính công bằng

Đã xử lý ở `nfrplan.md` Phần 10. FR tương ứng: **FR-AI-01** (bấm nút, không tự động) + **FR-AI-02** (tắt trong contest) + **FR-AI-03** (quota). Ba FR này là hình chiếu trực tiếp của ba NFR.

Bổ sung một mâu thuẫn chưa nhắc: **FR-AI-05 — AI không được đưa mã giải hoàn chỉnh.** Nếu AI viết luôn lời giải đúng, tính năng này biến từ công cụ học thành công cụ gian lận, và toàn bộ dữ liệu "đã giải bao nhiêu bài" của hệ thống mất ý nghĩa. Đây là ràng buộc **nghiệp vụ**, phải nằm trong prompt và có test kiểm chứng.

### 3.8 — Upload ZIP đề bài ↔ SEC2 và giới hạn đĩa

**FR bản năng:** *"SETTER upload testdata bằng file ZIP."*

**NFR bị phá:** SEC2 (zip bomb: file 1MB giải nén thành 100GB), R (đầy đĩa trên máy host — mà đây là laptop cá nhân), và Quy tắc 5 (upload 200MB không thể là request đồng bộ).

**Cách viết lại — FR-PROB-03** với giới hạn **nằm trong đặc tả, không phải trong config ẩn**: ≤200MB nén · ≤2GB sau giải nén · tỉ lệ nén ≤100:1 · ≤1000 testcase · chặn đường dẫn tuyệt đối, `..`, và symlink · validate `problem.yaml` trước khi ghi bất cứ file nào · chạy như job nền có tiến độ.

### 3.9 — Xem source người khác ↔ tính công bằng contest

**FR bản năng:** *"Người dùng xem được bài giải đã AC của người khác để học hỏi."*

**NFR bị phá:** tính công bằng — trong contest thì đây là gian lận trực tiếp.

**Cách viết lại:** ba trạng thái theo ma trận Quy tắc 3 — **trong contest: cấm tuyệt đối** · ngoài contest, đề thường: tuỳ cấu hình của đề, mặc định **tắt** · sau khi contest kết thúc: mở theo cấu hình. Kiểm ở tầng use-case, vì một API trực tiếp bỏ qua UI là chuyện 5 phút.

### 3.10 — SSE cho mọi trang ↔ 1000 kết nối đồng thời + giới hạn tunnel

**FR bản năng:** *"Mọi trang cập nhật realtime."*

**NFR bị phá:** A (Cloudflare Tunnel giới hạn thời gian sống kết nối), và tài nguyên host.

**Cách viết lại:** SSE **chỉ trên hai trang** — chi tiết bài nộp (FR-SUB-05) và bảng xếp hạng contest (FR-CON-04). Mọi trang khác dùng REST thường. **Fallback polling bắt buộc** ở cả hai, vì kết nối *sẽ* đứt.

### 3.11 — "Nộp bài không giới hạn" ↔ P4 và SEC2

**FR bản năng:** *"Người dùng nộp bài bao nhiêu lần tuỳ ý."*

**NFR bị phá:** P4 — một người viết script nộp 1000 lần/phút là chiếm trọn năng lực chấm của cả hệ thống.

**Cách viết lại — FR-SUB-08:** rate limit **là quy tắc nghiệp vụ được công bố**, không phải cơ chế kỹ thuật ẩn: 1 bài/10s/user, UI hiển thị đếm ngược. Người dùng hiểu và chấp nhận; một giới hạn ẩn thì bị coi là lỗi.

### 3.12 — Sửa đề sau khi đã có bài nộp ↔ tính toàn vẹn dữ liệu

**FR bản năng:** *"SETTER sửa đề và testdata bất cứ lúc nào."*

**NFR bị phá:** tính toàn vẹn — bài AC hôm qua có thể phải là WA hôm nay, nhưng bảng xếp hạng vẫn ghi AC.

**Cách viết lại — FR-PROB-10 và FR-PROB-11:** sửa mô tả đề thì tự do · **sửa testdata hoặc giới hạn thì bắt buộc sinh job rejudge**, có cảnh báo rõ số bài bị ảnh hưởng, ghi `audit_log` · **cấm hoàn toàn khi đề đang nằm trong contest đang chạy**.

---

## PHẦN 4 — Ma trận truy vết FR ↔ NFR

Đọc theo cột để trả lời "NFR này được bảo vệ bởi những FR nào", đọc theo hàng để trả lời "FR này bị ràng buộc bởi gì".

| Nhóm FR | P | S | SEC | R | U | A | M | C | AI |
|---|---|---|---|---|---|---|---|---|---|
| FR-AUTH | · | **●** | **●** | ● | · | · | · | · | · |
| FR-PROB | ● | ● | **●** | ● | ● | · | ● | ● | · |
| FR-SUB | **●** | **●** | **●** | **●** | **●** | ● | · | ● | · |
| FR-CON | **●** | ● | **●** | ● | ● | ● | ● | · | ● |
| FR-AI | ● | · | **●** | ● | ● | ● | ● | · | **●** |
| FR-ADM | **●** | ● | ● | **●** | ● | **●** | ● | · | ● |

**●** = có ràng buộc · **● đậm** = ràng buộc quyết định, sai là phải viết lại kiến trúc

**Ba ô đáng chú ý:**
- **FR-SUB × R** — nhóm FR lõi gánh yêu cầu độ tin cậy nặng nhất. Đây là lý do plan gốc bắt làm chung M1, không chia đôi.
- **FR-PROB × SEC** — mọi đường rò rỉ testdata đều bắt nguồn từ một FR trong nhóm này.
- **FR-ADM × A** — FR quản trị quyết định hệ thống có vận hành được lúc 2 giờ sáng giữa contest hay không.

---

## PHẦN 5 — FR bị loại vì mâu thuẫn NFR

Danh sách này quan trọng ngang danh sách FR được nhận. Mỗi dòng là một cuộc tranh luận không phải mở lại.

| FR bị loại | NFR mâu thuẫn | Lý do |
|---|---|---|
| **Custom checker do người dùng viết** | SEC1 | Chạy code tuỳ ý của người dùng **với quyền của hệ thống chấm** — phá vỡ toàn bộ mô hình sandbox. Plan gốc đã loại, giữ nguyên |
| **Bài tương tác (interactive)** | P7, SEC1 | Cần hai chiều stdin/stdout đồng thời trong sandbox, và làm việc đo CPU time trở nên không đáng tin |
| **Chống đạo văn (MOSS)** | M, thời gian | Là một dự án riêng, không phải một tính năng |
| **Xem toàn bộ bảng xếp hạng không phân trang** | P1, S3 | Xem mâu thuẫn #4 |
| **Xoá cứng tài khoản / bài nộp** | R1, audit | Xem mâu thuẫn #5 và #12 |
| **Hiển thị nội dung testcase ẩn cho tác giả bài nộp** | SEC3 | Xem mâu thuẫn #1 — đây là cái dễ bị nhân nhượng nhất, đừng nhân nhượng |
| **AI review tự động cho mọi bài** | AI2 | Xem mâu thuẫn #7 |
| **Thông báo email khi có kết quả** | A, M | Cần SMTP + hàng đợi email + xử lý bounce. SSE đã giải quyết đúng nhu cầu đó |
| **OAuth (Google/GitHub), 2FA** | M, thời gian | Plan gốc đã loại |
| **Đa ngôn ngữ giao diện (i18n)** | M, thời gian | Plan gốc đã loại |
| **Rating Elo** | M | Cần dữ liệu nhiều contest mới có ý nghĩa. v1.1 |

---

## PHẦN 6 — Gắn FR vào mốc, và cảnh báo về khối lượng

| Mốc | Tuần | FR bắt buộc xong | Ghi chú |
|---|---|---|---|
| **M1** | 1–2 | FR-SUB-02, 03, 04 · FR-PROB-01 (tối giản) | **Lõi. Không có FR nào khác được chen vào đây** |
| M2 | 3–4 | (không có FR mới — toàn bộ là NFR sandbox) | Đây là mốc thuần chất lượng |
| M3 | 3–6 | FR-SUB-05, 06 · FR-PROB-05, 06 | Realtime + checker + đa ngôn ngữ |
| M4 | 7–9 | FR-AUTH-01→08 · FR-PROB-02, 03, 04, 07, 08, 09 · FR-SUB-01, 07, 08, 10, 11 | **Mốc nặng nhất — 19 FR** |
| M5 | 10–12 | FR-CON-01→09 | FR-CON-10 là cái cắt đầu tiên |
| M6 | 10–12 | FR-ADM-01→06 · FR-SUB-09, 12 · FR-PROB-10, 11, 12 | |
| — | 14–15 | FR-AI-01→09 | Chỉ khi chọn phương án C ở `nfrplan.md` 10.7 |

> ⚠️ **Cảnh báo khối lượng:** M4 gánh 19 FR trong 3 tuần. Plan gốc ước lượng M4 là **82h** (tổng task 4.1–4.9), **chưa tính** FR-PROB-07 (`feedback_level`), FR-SUB-11, và phần SSE-qua-Redis mà `nfrplan.md` 3.2 bổ sung. Thực tế gần **95h** — với ~100h công của cả team trong 3 tuần thì kín lịch, không còn khoảng trống.
>
> Nếu tuần 8 thấy chậm, thứ tự cắt: **FR-PROB-12 → FR-SUB-10 → FR-PROB-06 (subtask) → FR-CON-10**. Tuyệt đối không cắt FR-PROB-07 — nó là biện pháp chống rò rỉ testdata, không phải tính năng.

---

## PHẦN 7 — Quy trình cho mọi FR mới trong tương lai

Khi một trong hai bạn (hoặc giảng viên hướng dẫn) đề xuất một tính năng mới, chạy qua sáu câu hỏi này **trước khi** ghi vào board:

1. **FR này có mô tả cách làm không?** Nếu có tên công nghệ hoặc động từ nội bộ → viết lại theo Quy tắc 1.
2. **Nó có trả về danh sách không?** Nếu có → phân trang, ghi giới hạn vào chính FR.
3. **Nó hiển thị dữ liệu gì, cho ai, khi nào?** Điền vào ma trận Quy tắc 3. Nếu có ô nào liên quan testdata → dừng lại, xem lại mâu thuẫn #1.
4. **Chạy hai lần thì sao?** Nếu chưa trả lời được → chưa xong đặc tả.
5. **Có thể vượt 5 giây không?** Nếu có → job nền, và phải trả lời được nó cạnh tranh tài nguyên với bài nộp trực tiếp thế nào.
6. **Nó chạm vào contest đang diễn ra không?** Nếu có → mặc định **cấm**, rồi mới bàn ngoại lệ.

Sáu câu này mất 10 phút. Mười hai mâu thuẫn ở Phần 3 đều sẽ bị bắt bởi ít nhất một trong sáu câu.

---

## Một câu cuối

Ba tài liệu của dự án này trả lời ba câu hỏi khác nhau: plan gốc trả lời *khi nào làm gì*, `nfrplan.md` trả lời *tốt đến mức nào*, tài liệu này trả lời *làm được những gì*.

Nhưng điều quan trọng nhất nằm ở chỗ giao nhau, và nó gọn trong một câu:

**Trong một Online Judge, phần lớn các tính năng nghe hợp lý nhất lại chính là các tính năng phá hoại hệ thống — vì thứ hệ thống này bán là sự công bằng, mà công bằng thì bị phá bởi những thứ trông rất giống lòng tốt.**

"Cho người dùng xem test họ làm sai" · "cho họ nộp bao nhiêu lần tuỳ thích" · "cho AI giải thích chỗ sai" · "cho họ xem bài giải của người khác" — cả bốn đều là thiện ý, và cả bốn đều nằm trong Phần 3 của tài liệu này.

---

## Phụ lục — Hai chỗ cần sửa trong `nfrplan.md`

Rà soát chéo phát hiện hai điểm không khớp, đã tính đến trong tài liệu này:

| Vấn đề | Chi tiết | Xử lý |
|---|---|---|
| **U3 thiếu trong bảng SLO** | `nfrplan.md` mục 6.1 định nghĩa U3 ("mọi verdict giải thích được") nhưng bảng SLO Phần 1 chỉ có U1, U2 | Thêm dòng `U3 · Verdict giải thích được · 7/7 loại · — · usability test` vào bảng Phần 1 |
| **Bảng 6.2 nói về WA quá lỏng** | Bản đó viết *"(nếu đề cho phép) input/expected/actual của test đó"* — cách diễn đạt này mở đường cho rò rỉ testdata | Thay bằng tham chiếu tới `feedback_level` (FR-PROB-07) và mâu thuẫn #1 của tài liệu này |

Ngoài ra lưu ý ký hiệu: **M1–M4 trong `nfrplan.md` là mã chỉ số Maintainability**, còn **M1–M6 trong plan gốc là mốc dự án**. Hai hệ trùng ký tự. Trong tài liệu này, mã NFR Maintainability được viết là `M4-nfr` để tránh nhầm.
