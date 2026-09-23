# Rà soát bảo mật toàn dự án — 2026-09-24, và kế hoạch

> Bổ sung cho `bao-mat-plan.md`, không thay nó. Tài liệu đó là **bản đồ lưới kiểm thử** và các
> lỗ trong lưới. Tài liệu này là một **lượt rà toàn bộ hệ thống đang chạy**: mã, cấu hình, máy
> chủ, CI, chuỗi cung ứng. Viết sau khi đóng xong Lỗ 8–11, các đợt Cloudflare 0/2/3 và vá
> 11 CVE phụ thuộc.
>
> **Cách rà:** mọi dòng "✅" dưới đây có một phép đo đi kèm — lệnh đã chạy, log đã đọc, hoặc test
> đã gỡ-cơ-chế-cho-đỏ. Không dòng nào chép từ javadoc. Lý do: ba lần trong hai ngày, chú thích
> trong repo này hứa mạnh hơn điều mã làm (`audit_log` append-only, "Trivy quét jar", tag
> `trivy-action@0.28.0`).

---

## PHẦN 1 — Tóm tắt

Hệ thống **không có lỗ nào đang mở mà người lạ trên internet khai thác được ngay**. Mọi cổng
công khai đã đo: `/internal` và actuator 404 ở mọi biến thể đường dẫn, TLS ≥ 1.2, secret đủ
mạnh, dịch vụ nội bộ chỉ nghe loopback, 0 CVE CRITICAL/HIGH có bản vá trong hai jar triển khai.

Còn lại **ba rủi ro đáng làm ngay**, xếp theo hậu quả:

| # | Rủi ro | Cần gì để khai thác | Hậu quả |
|---|---|---|---|
| **R1** | Mã người lạ thoát được sandbox → tới **toàn bộ thư mục home** của máy Mac | Thoát `isolate` **và** thoát container | Mọi secret prod, khoá SSH, credentials tunnel, toàn bộ bản sao lưu |
| **R2** | Dò mã TOTP của ADMIN không giới hạn nhờ **xoay IPv6** | Đã có mật khẩu ADMIN + một dải IPv6 | Chiếm quyền ADMIN trong khoảng ~6 giờ (ước tính, xem F2) |
| **R3** | CodeMirror nạp từ CDN **không SRI**, CSP mở cả `cdn.jsdelivr.net` | jsdelivr/npm bị xâm nhập, hoặc một lỗ XSS bất kỳ | Đọc JWT trong `localStorage` → chiếm tài khoản đang mở trang nộp bài |

---

## PHẦN 2 — Đã đo và ổn (không cần làm lại)

| Vùng | Bằng chứng (2026-09-24) |
|---|---|
| Secret prod | Đọc env tiến trình API trong bộ nhớ, chỉ in độ dài: JWT/internal/TOTP 64 ký tự; DB, Rabbit, Redis, MinIO secret 48 ký tự; **không cái nào trùng mặc định** trong `application.yml`. Profile `prod` → không nạp `db/dev-seed` |
| Bề mặt mạng | `lsof -iTCP -sTCP:LISTEN`: Postgres, Redis, RabbitMQ (+15672), MinIO (+9001), API 8080, actuator 8081, metrics cloudflared — **tất cả 127.0.0.1**. Không cổng nào của OJ nghe ra LAN |
| Tunnel / biên | `kiem-tunnel.sh` 36/36: `/internal` + actuator 404 ở đường dẫn chuẩn và mọi biến thể trong script (6 cho `/internal`, 3 cho actuator) · TLS 1.0/1.1 bị từ chối (`alert protocol version`) · HSTS · `no-store` trên `/api/v1` · đúng một connector |
| So secret nội bộ | `InternalSecretFilter` dùng `MessageDigest.isEqual` (thời gian hằng) + chặn mọi request mang header Cloudflare |
| Token | Refresh token lưu **SHA-256**, xoay vòng, dùng lại → thu hồi toàn bộ phiên · access 15 phút · TOTP chống dùng lại mã bằng câu ghi `last_step` có điều kiện (nguyên tử) · mã TOTP sai **có** tính là lần đăng nhập hỏng |
| Đăng nhập | Băm BCrypt **luôn** chạy kể cả khi không có tài khoản (không dò được tài khoản bằng thời gian) · cùng một câu lỗi cho sai tên / sai mật khẩu |
| SSE | Gửi token bằng header qua `fetch`, **không** đặt token trên URL (không lọt vào log Cloudflare/Tomcat) |
| CORS | Không có cấu hình CORS nào → trình duyệt chỉ cho cùng origin |
| Giải tuần tự | Redis chỉ `StringRedisTemplate`; worker nhận `Message` thô từ RabbitMQ — không có đường giải tuần tự Java nào |
| DOM XSS | Đúng một phép gán `innerHTML` trong toàn bộ JS (các chỗ khác là chú thích), gán `statementHtml` do server render (escape + lọc URL) |
| Log | Grep theo tên biến (`source`, `matKhau`, `password`, `token`, `secret`…) trên toàn bộ `oj-api` + `oj-worker`: không lời gọi `log.*` nào đưa chúng vào dòng log. **Đây là grep, không phải lưới** — chưa có luật nào ép (Lỗ 3) |
| Sandbox | 14/14 ca tấn công + 9/9 chấm thật trên **ảnh worker dựng lại hôm nay** · `--share-net`/`--full-env`/`-e`/`--inherit-fds` bị cấm bằng danh sách có test |
| Phụ thuộc | Trivy quét **hai fat jar** (`rootfs`): nhận ra 127 gói trong jar API, 47 trong jar worker, **0 CRITICAL/HIGH có bản vá**. Chốt "Cổng có thật sự đọc jar?" đỏ nếu Trivy thôi đọc `BOOT-INF/lib` |
| Lịch sử git | Quét toàn bộ `git log -p`: 5 dòng nghi → cả 5 là tên biến / hằng test giả. Chưa từng commit `.env`, `*.pem`, credentials |
| Sao lưu | Dump 15 phút + hằng ngày + WAL đều mới trong giờ đo |
| Ảnh hạ tầng | MinIO lấy từ quay.io ghim digest (Docker Hub `minio/minio` đã 404); `trivy-action` ghim SHA |

---

## PHẦN 3 — Phát hiện

Mỗi mục: **đường đi** (từ đâu, vai trò nào, tới gì) · **bằng chứng** · **mức**.

### F1 · 🔴 Thoát sandbox thì tới được toàn bộ home của máy Mac

- **Đường đi:** bài nộp → thoát `isolate` → root trong container `oj-worker` → thoát container
  → root trong máy ảo Linux của OrbStack → thư mục `/Users` được chia sẻ vào máy ảo.
- **Bằng chứng:** container thử với `-v /Users:/u:ro` **thấy** `Desktop/online-judge/.env`,
  `.ssh`, `.cloudflared/config.yml`, `oj-backup/ojdb_prod` (chỉ kiểm tồn tại, không đọc).
  Container worker chạy với `CAP_SYS_ADMIN`, `CAP_NET_ADMIN`, `seccomp=unconfined`,
  `apparmor=unconfined`, không mount gì — nên chặng khó nhất là thoát container, và các quyền
  trên làm chặng ấy dễ hơn mặc định rất nhiều.
- **Mức:** xác suất thấp (hai lớp phải cùng thủng; `isolate` qua 14 ca tấn công), **hậu quả
  toàn phần** — đúng thứ thứ ba trong ba thứ hệ thống này bán (CLAUDE.md §0).
- **Chưa đo:** OrbStack có cho giới hạn thư mục chia sẻ cho Docker engine không; quyền nào
  trong bốn `cap-add` là thật sự cần cho `isolate` (có thể `NET_ADMIN` thừa).

### F2 · 🔴 Trần dò mã TOTP chỉ tính theo IP — IPv6 vô hiệu hoá nó

- **Đường đi:** người có mật khẩu ADMIN (lộ, dùng lại, lừa đảo) → gửi mật khẩu đúng + mã TOTP
  đoán → mỗi địa chỉ được 5 lần sai/phút rồi khoá 15 phút → **đổi địa chỉ trong dải /64** →
  lặp lại.
- **Bằng chứng:** `LoginUseCase` ghi lần sai và gọi `khoaNeuQuaNhieu(clientIp)` — khoá **theo
  IP**, không theo tài khoản. `ClientIp` không gom IPv6 theo dải (grep: không có `/64`).
  Cùng gốc với Đợt 1 của kế hoạch Cloudflare (chưa làm).
- **Ước tính** (chưa đo): 10⁶ mã, 3 mã hợp lệ mỗi thời điểm (`Totp.CUA_SO_BUOC = 1` → bước hiện tại ±1) → ~3,3×10⁵ lượt kỳ vọng. Trần CPU
  là 4 phép BCrypt song song × ~250ms ≈ 16 lượt/giây → **khoảng 6 giờ**. Trần "1 lượt/2s/user"
  không giúp gì: nó chỉ chạy **sau** khi qua cả hai yếu tố.
- **Mức:** cao — đây là lớp bảo vệ cuối của quyền cao nhất (xem Lỗ 8). Cùng lỗ IPv6 cũng làm
  trần đăng ký 10/giờ/IP và trần API ẩn danh 600/phút/IP mất tác dụng.

### F3 · 🟠 CSP mở cả `cdn.jsdelivr.net`, và CodeMirror nạp không SRI

- **Đường đi (a):** jsdelivr hoặc gói npm bị xâm nhập → `editor.js` `import()` bundle `+esm`
  → chạy trong trang nộp bài → đọc JWT trong `localStorage`.
  **(b):** bất kỳ lỗ XSS nào sau này → nạp một thư viện tuỳ ý từ jsdelivr (CSP cho phép cả
  host) → vượt `script-src`.
- **Bằng chứng:** `editor.js:17-37` import 5 module từ `cdn.jsdelivr.net/npm/...+esm`.
  Bundle `+esm` do jsdelivr **dựng lúc phục vụ**, không phải file bất biến của npm, và
  `import()` động **không gắn được** `integrity=`. Bốn thẻ tĩnh (KaTeX ×3, qrcode) thì **có** SRI.
- **Mức:** trung bình. Đường (a) cần bên thứ ba bị xâm nhập; đường (b) cần một lỗ XSS trước.

### F4 · 🟠 Nhánh `main` không có branch protection

- **Bằng chứng:** `GET /repos/.../branches/main` → `"protected": false`. Repo **công khai**.
- **Hệ quả:** `ci`, `sandbox-attack`, `quet-phu-thuoc` chỉ báo đỏ, không chặn merge. Công tắc
  Dependabot *security updates* chưa kiểm được (cần quyền admin).
- **Mức:** trung bình — không phải lỗ khai thác được, mà là cổng không đóng.

### F5 · 🟠 Sao lưu: thư mục WAL ghi được bởi mọi người, dump không mã hoá, chưa có bản ngoài máy

- **Bằng chứng:** `~/oj-backup/wal` là `drwxrwxrwx` — do chính `kiem-wal.sh` hướng dẫn
  `chmod 777`. Dump `*.dump` là `644`, không mã hoá. `sao-luu-db.sh` đã cảnh báo thiếu bản
  off-site.
- **Đường đi:** tiến trình bất kỳ trên máy (hoặc trong máy ảo Docker, xem F1) xoá/cài file WAL
  → lần khôi phục theo thời điểm dựng lại một database đã bị sửa, hoặc không dựng được.
- **Mức:** trung bình với WAL; thấp với quyền dump (máy chỉ một người dùng, home `750`).

### F6 · 🟡 Máy chủ: firewall macOS tắt, AirPlay Receiver nghe trên LAN

- **Bằng chứng:** `socketfilterfw --getglobalstate` → *disabled*. `ControlCenter` nghe
  `*:5000` và `*:7000`.
- **Mức:** thấp — không phải dịch vụ OJ, nhưng là bề mặt thừa trên máy chạy mã người lạ.

### F7 · 🟡 Mặc định yếu trong `application.yml` không làm API từ chối khởi động

- **Bằng chứng:** `OJ_DB_APP_PASSWORD:ojpass`, `OJ_RABBIT_PASSWORD:ojpass`,
  `OJ_REDIS_PASSWORD:` (rỗng), `OJ_MINIO_SECRET_KEY:ojminio123`. Prod hôm nay đặt đủ (đo ở
  Phần 2) — nhưng quên một biến lúc dựng lại máy thì API **chạy bằng mật khẩu yếu** thay vì
  crash. Trái với lập trường "thiếu secret thì crash lúc boot" mà phần còn lại dự án giữ.
- **Mức:** thấp (dịch vụ chỉ nghe loopback).

### F8 · 🟡 Chưa xác nhận hostname Turnstile bằng một lượt đăng ký thật

- Test dùng phản hồi giả. Nếu Cloudflare trả hostname khác `onlinejudge67.click` một chút,
  **mọi** lượt đăng ký bị từ chối (log có dòng WARN nói rõ hostname nhận được). Rủi ro sẵn sàng,
  không phải rủi ro bảo mật.

---

## PHẦN 4 — Lỗ cũ còn mở (từ `bao-mat-plan.md`, đã kiểm lại hôm nay)

| Lỗ | Trạng thái đo 2026-09-24 |
|---|---|
| **3** · Bất biến #9 chỉ canh ở `toString()` | Vẫn mở — không có LUẬT 10, không `ListAppender`, không script log |
| **4** · "Đúng một `innerHTML`" chỉ là lời hứa | Vẫn mở — không test nào nhắc `innerHTML` |
| **5** · SEC3 toàn bề mặt | Vẫn mở — chưa có biên bản |
| **6** · Buổi tấn công chéo | Vẫn mở — cần 2 người × 3h |
| **7** · Checklist OWASP ký | Bản điền sẵn có ở `bao-mat-plan.md` Phần 4, chưa ký |
| — · Đề nằm trong hai kỳ thi chồng giờ | Đã tách thành việc riêng (chip), **chưa kiểm** đã làm chưa |

---

## PHẦN 5 — Kế hoạch

Thứ tự theo **hậu quả chia cho công sức**, không theo mức CVSS. Ai làm: **tay** = anh bấm
(GitHub, dashboard, máy chủ) · **mã** = tôi làm được · **quyết** = cần anh chọn trước.

### Đợt A · Bấm tay, không có mã — ~30 phút

| # | Việc | Ai | Đạt khi |
|---|---|---|---|
| A1 | Branch protection cho `main`: bắt buộc `ci`, `sandbox-attack`, `quet-phu-thuoc`, `buildx` xanh trước khi merge | tay | `GET /branches/main` → `"protected": true` |
| A2 | Bật Dependabot *security updates* (Settings › Code security) | tay | Trang Settings hiện "Enabled" |
| A3 | Bật firewall macOS + stealth mode; tắt AirPlay Receiver (System Settings › General › AirDrop & Handoff) | tay | `socketfilterfw --getglobalstate` = enabled; `lsof` không còn `*:5000`, `*:7000` |
| A4 | Đăng ký thử một tài khoản trên site | tay | Đăng ký qua; `grep 'hostname' ~/oj-release/api.log` không có WARN Turnstile |
| A5 | Dọn đồ lùi: jar cũ trong `~/oj-release`, ảnh `oj-worker:truoc-cve-20260924` | tay | Chỉ còn jar đang chạy + một bản trước |

### Đợt B · Chặn dò TOTP và vá IPv6 — mã, ~1 ngày · **cần quyết trước**

| # | Việc | Đạt khi |
|---|---|---|
| B1 | `ClientIp` thêm **khoá giới hạn**: IPv4 giữ nguyên, IPv6 gom theo dải, `::ffff:a.b.c.d` quy về IPv4. Log vẫn ghi địa chỉ đầy đủ | Unit: hai địa chỉ cùng dải → cùng khoá |
| B2 | Áp khoá mới cho: khoá đăng nhập sai (FR-AUTH-08), trần đăng ký, trần API ẩn danh | IT: 5 lần sai từ 5 địa chỉ **cùng dải** → bị khoá |
| B3 | **Trần theo tài khoản cho bước 2FA**: N lần mã sai liên tiếp → khoá riêng bước TOTP của tài khoản ấy trong T phút; ghi `audit_log`. Không ai khoá được người khác bằng cách này — muốn tới bước TOTP phải có mật khẩu | IT: N mã sai từ N IP khác dải → lần N+1 bị từ chối dù mã đúng; hết T phút thì qua |
| B4 | Gỡ-cơ-chế-cho-đỏ cho B1–B3 | Mỗi test đỏ khi bỏ bước gom dải / bỏ trần tài khoản |

**Cần anh quyết:**
- Dải IPv6: **/64** (khuyến nghị: dải nhỏ nhất một hộ gia đình được cấp, không gom nhầm hai
  hộ) hay /56?
- **N và T** cho B3 — con số mới, thuộc CLAUDE.md §5.4. Khuyến nghị **N = 10, T = 15 phút**:
  người thật gõ sai 10 mã liên tiếp gần như không xảy ra, còn kẻ dò thì tụt từ ~16 lượt/giây
  xuống 10 lượt/15 phút — từ ~6 giờ thành hàng chục năm.
- B2 đổi migration (`login_attempts` đếm theo dải) → `V15`, chạy trên DB rỗng và DB có dữ liệu.

### Đợt C · Bỏ CDN khỏi đường chạy script — mã, ~nửa ngày · **cần quyết trước**

| # | Việc | Đạt khi |
|---|---|---|
| C1 | Đưa CodeMirror (5 module), KaTeX, qrcode-generator vào `static/vendor/`, ghim phiên bản, ghi SHA-256 từng file | Trang chạy khi **chặn** `cdn.jsdelivr.net` ở trình duyệt |
| C2 | CSP: `script-src 'self' https://challenges.cloudflare.com`; bỏ jsdelivr khỏi `style-src`, `font-src` | `SecurityHeadersFilterTest` khẳng định chuỗi CSP **không** chứa `jsdelivr` |
| C3 | Test đếm: không file HTML/JS nào trong `static/**` tham chiếu host ngoài danh sách cho phép | Thêm một `<script src="https://…">` lạ → đỏ |

**Cần anh quyết:** cách vendor CodeMirror. Nó là ESM nhiều module; dự án không có bước build
JS. Hai cách: **(a)** tải đúng các bundle `+esm` hiện dùng, commit kèm SHA-256 (nhanh, không
thêm công cụ) · **(b)** thêm một bước `esbuild` một lần để gộp một file (sạch hơn, thêm công
cụ vào repo). Khuyến nghị (a).

### Đợt D · Cô lập máy chấm khỏi dữ liệu máy chủ — hạ tầng · **cần quyết, cần đo trước**

Bước đo, làm trước mọi quyết định:

| # | Đo | Mục đích |
|---|---|---|
| D0a | OrbStack có tắt/giới hạn được thư mục chia sẻ cho Docker engine không | Biết phương án rẻ nhất có tồn tại không |
| D0b | Bỏ lần lượt từng `cap-add` (bắt đầu `NET_ADMIN`), thay `seccomp=unconfined` bằng profile mặc định + ngoại lệ tối thiểu → chạy `kiem-sandbox.sh` | Biết quyền nào thật sự cần; mỗi quyền bỏ được là một đường thoát bớt đi |

Sau đó, ba phương án:

- **(a) Siết container** theo D0b, giữ nguyên OrbStack. Rẻ; giảm xác suất thoát container, **không**
  đổi hậu quả khi đã thoát.
- **(b) Worker trong máy ảo Linux riêng không chia sẻ thư mục** (Lima/UTM, hoặc OrbStack nếu
  D0a cho phép). Thoát sandbox chỉ tới một máy ảo không có gì. Tốn công dựng + đo lại hiệu
  chuẩn `host_factor` (ADR 006).
- **(c) Máy chấm là một máy riêng.** Sạch nhất, tốn phần cứng.

Khuyến nghị: **(a) ngay**, và **(b)** khi có một buổi — (b) là phương án duy nhất đổi được hậu
quả của F1 mà không cần phần cứng mới.

### Đợt E · Sao lưu — ~2 giờ · **cần quyết**

| # | Việc | Đạt khi |
|---|---|---|
| E1 | Thư mục WAL: thử `chown 70:70` + `700` thay `777`, chạy `kiem-wal.sh` | WAL mới vẫn được lưu; `ls -ld` không còn `rwxrwxrwx`. Hỏng thì ghi rõ vì sao OrbStack buộc 777 |
| E2 | Dump và base backup tạo với `umask 077` | File mới là `600` |
| E3 | Mã hoá bản sao (age/gpg) + đẩy một bản ra ngoài máy | Khôi phục được từ bản ngoài máy trên một DB rỗng (`khoi-phuc-db.sh`) |

**Cần anh quyết:** nơi để bản ngoài máy, và giữ khoá giải mã ở đâu (không được nằm cùng máy —
xem F1).

### Đợt F · Lưới kiểm thử còn thiếu — kế thừa `bao-mat-plan.md` Phần 3

| # | Việc | Nguồn |
|---|---|---|
| F-1 | LUẬT 10 (cấm `.source()`/`.password()`/`.token()`… vào lời gọi log) + IT bắt log thật trên đường nộp bài → verdict | Lỗ 3 |
| F-2 | Luật "đúng một `innerHTML`" trong test frontend | Lỗ 4 |
| F-3 | Quét SEC3 toàn bề mặt, ra biên bản | Lỗ 5 |
| F-4 | Ký checklist OWASP (bản điền sẵn đã có) | Lỗ 7 |
| F-5 | Buổi tấn công chéo 2 × 3h — thêm vào kịch bản: F2 (dò TOTP qua IPv6) và biến thể đường dẫn `/internal` | Lỗ 6 |
| F-6 | Kiểm việc "đề trong hai kỳ thi chồng giờ" đã làm chưa | chip riêng |
| F-7 | `application.yml`: ở profile `prod`, thiếu mật khẩu DB/Rabbit/Redis/MinIO thì **từ chối khởi động** thay vì dùng mặc định | F7 |

### Thứ tự đề xuất

**A → B → C → D0 → E → D → F.** A không tốn mã và đóng F4/F6/F8. B đóng rủi ro cao nhất mà
chỉ cần mã. C nhỏ và đóng R3. D0 chỉ là đo, nhưng mọi quyết định của D phụ thuộc nó.

---

## PHẦN 6 — Không làm, và vì sao

- **WAF riêng / IDS.** Cloudflare đã đứng trước; Mac không mở cổng nào (đo ở Phần 2). Thêm một
  lớp nữa không đóng rủi ro nào trong danh sách trên.
- **Chuyển JWT sang cookie `HttpOnly`.** Đóng đường đọc token của F3 nhưng mở CSRF và đổi hợp
  đồng API (`SessionResponse` đã ghi lý do). Đợt C đóng F3 tận gốc mà không đổi hợp đồng.
- **Chạy lại toàn bộ rà soát theo lịch.** Những gì lặp lại được đã thành máy: `kiem-tunnel.sh`,
  Trivy hằng ngày, `sandbox-attack.yml`. Phần còn lại (F1, cấu hình máy) đổi khi đổi hạ tầng —
  rà lại vào lúc ấy.
