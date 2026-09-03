# Kế hoạch giao diện — bắt kịp backend

> Đọc `CLAUDE.md` gốc trước. File này là kế hoạch, không phải đặc tả: mỗi bước dưới đây
> vẫn phải đi qua sáu câu hỏi ở mục 4 của `CLAUDE.md` trước khi viết dòng đầu tiên.

---

## 0 · Vì sao có file này

Giao diện dừng ở **Bước 4.12 (M4, tuần 9)**. Backend đã đi tới **M6**. Khoảng cách:

| | Backend | Giao diện |
|---|---|---|
| Endpoint công khai | ~40 | gọi **8** |
| Trang | — | **4** (`index` · `login` · `problem` · `submission`) |
| M5 contest | xong | **không có gì** |
| M6 vận hành | xong | **không có gì** |

Hệ quả cụ thể, không trừu tượng: một người dùng bình thường hiện **không xem được lịch sử
bài nộp của mình**, **không đổi được mật khẩu**, và **không biết máy chấm còn sống không** —
dù cả ba endpoint đã chạy từ tuần 9. Toàn bộ kỳ thi thì vô hình.

---

## 1 · Bốn quyết định nền — chốt trước, đừng bàn lại ở từng bước

### 1.1 · Giữ nguyên "không build step"

Không thêm React/Vue/Vite/Node. Lý do không phải khẩu vị:

- Deploy phải là **đúng một file jar** (`README`, `nfrplan` C1). Một toolchain thứ hai là
  một thứ nữa có thể hỏng lúc 2 giờ sáng trước kỳ thi.
- CI đang < 10 phút và không có Node. Thêm vào là thêm một trục phụ thuộc mới vào đúng
  hàng rào đang bảo vệ ba bất biến.
- 1272 dòng hiện tại đã phủ phần khó nhất (SSE, CodeMirror, refresh token). Cái còn thiếu
  là **biểu mẫu và bảng** — đúng thứ mà framework giúp ít nhất.

Đổi lại phải chấp nhận: không có type checking, và mọi ràng buộc phải ép bằng test ở 1.3.

### 1.2 · Một chỗ duy nhất biết hình dạng API

Thêm `js/duong-dan.js`: **mọi** đường dẫn và hình dạng payload nằm ở đây, không rải trong
từng trang.

Lý do rất cụ thể. Khi kiểm thử tay backend, ba lần liên tiếp tôi gọi sai và server trả lỗi
đúng nhưng khó lần: trường đăng nhập là `dinhDanh` chứ không phải `handle`; nộp bài nhận
`problemId` (số) chứ không phải `problemCode`; và `GET /api/v1/submissions` nhận `limit`
trong khi `problems`/`jobs`/`audit-log` nhận `size`. **Sai tên tham số phân trang thì server
im lặng bỏ qua** — bạn nhận 20 dòng và tưởng phân trang chạy đúng.

Một hằng viết sai ở một chỗ thì sửa một lần. Viết rải ở tám trang thì sửa bảy lần và quên
một.

### 1.3 · Ép bằng CI, không bằng lời hứa

Thêm `BeMatFrontendTest` (Java, không cần Node): quét mọi chuỗi `/api/v1/...` trong
`static/js/*.js` và khẳng định mỗi đường dẫn khớp một `@RequestMapping` có thật.

Cùng tinh thần `HttpSurfaceTest` và `HopDongVanHanhTest`, và bắt đúng loại lỗi mà trình
biên dịch không thấy: backend đổi tên một đường dẫn, frontend vẫn "biên dịch" được, và
triệu chứng duy nhất là một nút không làm gì cả.

### 1.4 · a11y mức A từ dòng đầu, không phải cuối

`build-order` Phần 14 xếp "đẹp giao diện" vào nhóm cắt trước, nhưng ghi rõ **giữ a11y mức
A** — "rẻ khi làm từ đầu, đắt gấp 10 khi sửa sau". CSS hiện tại đã có nền: tương phản
≥ 4.5:1, chạm ≥ 44px, `prefers-reduced-motion`, và **verdict không bao giờ chỉ dùng màu**.
Mọi trang mới kế thừa, không phát minh lại.

---

## 2 · Tám trang mới, mười hai bước, ba đợt

Thứ tự theo một nguyên tắc: **cái người dùng không thể tự xoay xở thì làm trước; cái hai
người vận hành có thể làm bằng `curl` thì làm sau.**

### Đợt 1 — trả lại những gì backend đã có từ tuần 9

Ba trang nhỏ, mỗi trang khoảng nửa ngày. Chúng đóng những lỗ mà người dùng gặp hằng ngày.

| # | Trang | Endpoint | FR |
|---|---|---|---|
| **G1** | `bai-nop.html` — lịch sử bài nộp của mình | `GET /api/v1/submissions` (cursor, `limit`, lọc đề/verdict/ngôn ngữ) | FR-SUB-07 |
| **G2** | `ho-so.html` — hồ sơ + đổi mật khẩu | `GET`/`PATCH /api/v1/me` · `POST /api/v1/me/password` | FR-AUTH-04, 05 |
| **G3** | `trang-thai.html` — trang trạng thái công khai | `GET /api/v1/status` | FR-ADM-05 |

Đợt này cũng dựng ba module dùng chung cho tất cả những gì sau: `js/trang.js` (khởi động
trang, chốt đăng nhập, ô báo lỗi), `js/phan-trang.js` (cursor — bốn trang sẽ dùng),
`js/duong-dan.js` (mục 1.2).

**G3 có một ràng buộc dễ bỏ sót:** khi `dangNhanBai: false` (FR-ADM-06, chế độ bảo trì),
nút nộp bài ở `problem.html` phải **tắt kèm lý do**, chứ không để người dùng bấm rồi nhận
một lỗi bất ngờ.

### Đợt 2 — kỳ thi (M5), phần lớn nhất và rủi ro nhất

| # | Trang | Endpoint | FR |
|---|---|---|---|
| **G4** | `contests.html` — danh sách kỳ thi | ⚠️ **endpoint chưa có** — xem mục 6 | FR-CON-01 |
| **G5** | `contest.html` — chi tiết + đăng ký + danh sách đề | `GET /api/v1/contests/{slug}` · `POST .../register` | FR-CON-02, 03 |
| **G6** | Bảng xếp hạng trong `contest.html` — REST trước | `GET .../standings` | FR-CON-04, 06 |
| **G7** | Bảng xếp hạng qua SSE + hiển thị đóng băng | `GET .../standings/stream` | FR-CON-04, 05 |
| **G8** | Tạo kỳ thi + thêm đề + công bố (ADMIN) | `POST /api/v1/contests` · `.../problems` · `.../reveal` | FR-CON-01, 07 |

**G6 trước G7 là có chủ ý.** `oj-api/CLAUDE.md` mục 4: fallback REST là **bắt buộc**, không
phải phương án dự phòng — Cloudflare Tunnel sẽ cắt kết nối. Viết REST trước thì fallback là
đường đã chạy thật; viết SSE trước thì fallback là đoạn code chưa ai chạy bao giờ, và nó
được chạy lần đầu vào đúng lúc kết nối đứt giữa kỳ thi.

### Đợt 3 — ra đề và vận hành

| # | Trang | Endpoint | FR |
|---|---|---|---|
| **G9** | `ra-de.html` — tạo/sửa/xuất bản/gỡ đề, đặt `feedback_level` | `POST`/`PUT /api/v1/problems` · `/edit` · `/publish` · `/retire` | FR-PROB-01, 07, 08 |
| **G10** | Nạp testdata trong `ra-de.html` + tiến độ job + tải về | `POST`/`GET /api/v1/problems/{id}/testdata` · `GET /api/v1/jobs/{id}` | FR-PROB-03, 04, 10, 12 |
| **G11** | `quan-tri.html` — dashboard · rejudge · người dùng · bảo trì | `GET /api/v1/admin/ops` · `POST .../rejudge` · `.../users/{id}/role` `.../active` | FR-ADM-01, 03, 04, 06 · FR-SUB-09 |
| **G12** | `nhat-ky.html` — audit log | `GET /api/v1/admin/audit-log` | FR-ADM-02 |

**Vì sao đợt này đứng cuối:** hai người vận hành gọi được `curl`, người dùng thì không.
Đánh đổi đã biết: cho tới G9, thêm một đề mới vẫn là một lệnh `curl` multipart với gói ZIP.
Nếu kế hoạch chạy contest thật đến sớm hơn dự kiến, **đảo G9–G10 lên trước Đợt 2** — đó là
lần đảo thứ tự duy nhất trong file này không kéo theo hệ quả nào khác.

---

## 3 · Bảy cạm bẫy riêng của backend NÀY

Đây là phần đáng đọc nhất. Chúng không phải lời khuyên chung về frontend.

### 3.1 · 404 là cố ý — đừng "giúp" người dùng bằng một câu rõ hơn

Backend trả **404** cho bài nộp của người khác và cho đề của kỳ thi chưa mở, với **đúng câu
chữ như khi vật thể không tồn tại**. Đó không phải thiếu sót cần frontend bù.

Một lập trình viên tử tế sẽ muốn đổi thành "Bạn không có quyền xem bài này". Câu đó biến
404 thành một **máy tra**: gọi thử 10.000 id và đếm xem id nào trả "không có quyền" là biết
chính xác ai đã nộp bài nào, và đề nào tồn tại trong kỳ thi chưa mở. Giữ nguyên câu của
server (`api.js` đã đưa thẳng `message` ra ngoài — mục 2 của javadoc file đó).

### 3.2 · Đừng suy ra thứ server cố ý không gửi

`feedback_level` quyết định người nộp thấy gì. Server đã lọc; frontend chỉ được **hiển thị
cái đã nhận**, không được dựng chỗ trống nói lên điều gì.

Cụ thể: `failedTestOrdinal: null` thì hiện "không công bố", **đừng** hiện "sai ở test ?/40"
— con số 40 là thông tin về bộ test mà mức `NONE` cố ý không cho biết. Đây là bất biến #1,
và nó bị phá bằng những thứ trông rất giống lòng tốt.

### 3.3 · `PAUSED` là trạng thái bình thường, không phải lỗi

Job rejudge **tạm nghỉ liên tục theo thiết kế**: mỗi lô đẩy nhiều nhất `max-in-flight` bài
rồi tự phanh nhường chỗ cho bài nộp trực tiếp. Ở trạng thái đó `error_message` mang một
**lời giải thích**, không phải một sự cố:

> "Đã đủ 2 bài chấm lại đang chờ (trần 30% năng lực). Chạy tiếp khi máy chấm rút bớt —
> đây là nhịp bình thường, không phải sự cố."

Tô đỏ nó là nói với người vận hành rằng có sự cố trong khi mọi thứ đang chạy đúng.
`PAUSED` → màu chờ + văn bản; chỉ `FAILED` mới là đỏ.

### 3.4 · Đóng băng phải nhìn thấy được

Trong thời gian freeze, `GET .../standings` trả `dongBang: true` và mỗi dòng có
`soBaiChoSauFreeze`. Nếu trang không nói gì, người dùng thấy một bảng ngừng đổi và kết
luận hệ thống hỏng — đúng vào lúc căng nhất.

Hiện rõ: "Bảng đã đóng băng lúc HH:MM" + cột số bài đang chờ sau đóng băng. Đó cũng chính
là nghi thức của một kỳ thi ICPC, không phải một thông báo kỹ thuật.

### 3.5 · 429 phải là đếm ngược, không phải thông báo

FR-SUB-08 ghi rõ rate limit là **quy tắc nghiệp vụ được công bố**, và UI phải hiện đếm
ngược. `problem.js` đã làm đúng: `demNguocLai(Number(e.retryAfter) || 10)`. Luật ở đây là
**giữ nguyên khuôn ấy** cho mọi nút gửi mới — một nút chỉ hiện "429 Too Many Requests" là
một nút bắt người dùng tự đoán phải chờ bao lâu.

### 3.6 · Mọi chuỗi từ server đi qua `chu()`

`khung.js` đã đặt luật: `textContent`, không `innerHTML`. Ngoại lệ duy nhất là
`statementHtml` (server đã escape). Các trang mới mang thêm dữ liệu người dùng nhập vào
DOM — tên kỳ thi, handle, `details` của audit log, `error_message` của job. Trang này giữ
access token trong `localStorage`, nên một chỗ `innerHTML` là một lần mất phiên của mọi
người đang xem.

### 3.7 · Hai luồng SSE, một luật

Sau G7 sẽ có hai luồng (bài nộp, bảng xếp hạng). `sse.js` đã dùng `fetch` thay
`EventSource` để token nằm trong header — **giữ nguyên**, đừng vì tiện mà dùng
`EventSource` cho luồng thứ hai: nó không đặt được header, nên token sẽ phải vào query
string, tức là vào log proxy và lịch sử trình duyệt.

---

## 4 · Định nghĩa "xong" cho một trang

- [ ] Không đường dẫn nào viết rải — tất cả qua `js/duong-dan.js`
- [ ] `BeMatFrontendTest` xanh
- [ ] Mọi chuỗi từ server qua `chu()`; không `innerHTML` mới
- [ ] Danh sách có phân trang cursor và đúng tên tham số (`size` hay `limit` — kiểm lại)
- [ ] Lỗi hiện `message` của server, không tự dịch mã lỗi
- [ ] Đi được bằng bàn phím; link "bỏ qua tới nội dung chính" nằm trong HTML tĩnh
- [ ] Một cột trên điện thoại, không cần cuộn ngang
- [ ] `axe DevTools` không báo lỗi mức A

---

## 5 · Khi chậm thì cắt theo thứ tự này

Nối tiếp `build-order` Phần 14:

```
cắt trước  →  G12 nhật ký (đọc bằng psql được)
              G8  tạo kỳ thi qua UI (curl được)
              G10 tải testdata về (FR-PROB-12 vốn đã ở đầu danh sách cắt)
              đẹp giao diện
              ────────── ranh giới ──────────
KHÔNG cắt  →  a11y mức A
              fallback REST của cả hai luồng SSE
              đếm ngược 429
              hiển thị đóng băng
              giữ nguyên câu 404 của server
```

---

## 6 · Hai việc nhỏ nên sửa ở backend, không phải bù ở frontend

1. **`GET /api/v1/submissions` nhận `limit`**, trong khi `problems` · `jobs` · `audit-log`
   đều nhận `size`. Một tên là đủ. Sai tên thì server bỏ qua trong im lặng, nên đây là loại
   lệch không bao giờ tự lộ ra.
2. **`GET /api/v1/contests` KHÔNG TỒN TẠI.** `ContestController` chỉ có `/{slug}`,
   `/{contestId}/standings`, `register`, `POST` tạo, thêm đề, và `reveal`. Không có
   use-case liệt kê nào trong `contests.application.usecase`.

   Nghĩa là hiện tại **không có cách nào tìm ra một kỳ thi ngoài việc biết trước `slug`**.
   Đây không phải việc frontend bù được: G4 chặn ở đây.

   Việc cần làm trước G4 — nhỏ, nhưng là việc backend:

   ```
   ListContestsUseCase  +  GET /api/v1/contests?cursor=&size=
   ```

   Ba ràng buộc bắt buộc, và ràng buộc thứ ba là ràng buộc công bằng:

   * phân trang cursor, trần `page.max-size` (bất biến #8);
   * `CursorPage<ContestSummary>`, không trả `List`;
   * **kỳ thi chưa bắt đầu chỉ được lộ tên, giờ và trạng thái đăng ký — không lộ danh
     sách đề.** `ContestResponses.ChiTiet` hiện mang danh sách đề, nên tái dùng nó cho
     trang danh sách là lộ đề của kỳ thi chưa mở qua đúng cái endpoint dùng để tìm kỳ thi.
     Cần một DTO tóm tắt riêng.

Cả hai đều nhỏ, nhưng cả hai đều là thứ frontend sẽ phải viết một chỗ đặc biệt để bù, và
một chỗ đặc biệt thì không ai nhớ.
