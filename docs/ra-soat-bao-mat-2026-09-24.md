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
  trong bốn `cap-add` là thật sự cần cho `isolate`. **→ Đo ở Phần 7: `NET_ADMIN` CẦN; seccomp `unconfined` thì không.**

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
- **(b) Worker trong máy ảo Linux riêng, KERNEL RIÊNG, không chia sẻ thư mục** (UTM, hoặc Lima dùng
  Virtualization.framework). **Không** dùng "isolated machine" của OrbStack — chung kernel, chính
  OrbStack nói không dành cho mã cố thoát sandbox (Phần 7). Thoát sandbox chỉ tới một máy ảo không có gì. Tốn công dựng + đo lại hiệu
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

---

## PHẦN 7 — Tiến độ thực hiện (2026-09-24, cùng ngày)

Làm theo khuyến nghị của Phần 5: dải **/64**, trần TOTP **10 lần / 15 phút**, vendor theo cách **(a)**.
Mỗi đợt có gỡ-cơ-chế-cho-đỏ; số ca đỏ ghi trong commit tương ứng.

| Đợt | Trạng thái | Bằng chứng |
|---|---|---|
| **A** | ⏳ việc tay — xem cuối phần này | — |
| **B** | ✅ `10daa81` | `ClientIp.khoaGioiHan` (IPv6 /64) cho khoá đăng nhập, trần đăng ký, trần API ẩn danh; khoá đăng nhập dùng `<<=`/`>>=` trên `inet` (không migration). V15: 10 mã sai liên tiếp / tài khoản → khoá bước 2FA 15 phút, kể cả mã đúng; `noRollbackFor` cho đường tắt 2FA. 6 biến thể gỡ-cơ-chế đều đỏ; biến thể `noRollbackFor` **chỉ IT** thấy. Kèm: `ClientIp.hopLe` từng nhận `bad.cafe` và tra DNS 211ms |
| **C** | ✅ `964a5fd` | 43 module CodeMirror + KaTeX + qrcode vào `static/vendor/` (giống từng byte CDN, 4 SRI cũ khớp), `SHA256SUMS`; CSP chỉ `'self'` + Turnstile. Mở thật trong trình duyệt với CSP mới: 0 request ra ngoài, 0 lỗi CSP, KaTeX render + font nạp |
| **D0** | ✅ đo xong | Xem "D0 — kết quả" dưới |
| **D(a)** | ✅ worker đang chạy với seccomp mặc định | `/proc/1/status` trong container: `Seccomp: 2`; 14/14 tấn công + 9/9 chấm thật trên đúng bộ cờ ấy |
| **D(b)/(c)** | ⏳ cần quyết | Xem D0 — OrbStack "isolated" KHÔNG đủ |
| **E1–E2** | ✅ | `~/oj-backup` 700, không file nào đọc được bởi người khác (trước: WAL `777`, 19 file 644); `umask 077` trong hai script sao lưu (file mới ra `600` — đo) |
| **E3** | ⏳ cần quyết | nơi để bản ngoài máy + nơi giữ khoá |
| **F-1** | ✅ | `LogKhongBiMatTest` + `LogKhongLoBiMatIT` (Lỗ 3) |
| **F-2** | ✅ | luật "một innerHTML" (Lỗ 4) |
| **F-3** | ✅ | biên bản SEC3 dưới |
| **F-4, F-5** | ⏳ việc người | ký OWASP; buổi tấn công chéo 2×3h |
| **F-6** | ❌ chưa làm | "đề trong hai kỳ thi chồng giờ": không commit nào ngoài `ae7e81c` chạm việc ấy — chip vẫn chờ |
| **F-7** | ✅ | `KhongMatKhauMacDinhOProd`: profile prod mà mật khẩu DB/Rabbit/Redis/MinIO còn mặc định hoặc rỗng → không khởi động. Đo trước trên env prod thật: cả 6 khoá QUA |

### D0 — kết quả đo

- **Bỏ `NET_ADMIN` → 21/23 đỏ** (`SIOCSIFFLAGS on 'lo' failed`): isolate phải bật `lo` trong
  network namespace của box. Nhận định "có thể `NET_ADMIN` thừa" ở F1 là **sai** — quyền ấy cần.
- **Seccomp mặc định của Docker → 23/23 xanh.** Đã áp (`trien-khai-mac.sh`, `kiem-sandbox.sh`).
- **AppArmor:** máy ảo OrbStack không có (`docker info` chỉ liệt kê seccomp) — cờ `unconfined` là no-op, đã bỏ.
- **OrbStack "isolated machine" KHÔNG phải ranh giới cho mô hình đe doạ của ta.** Tài liệu của chính
  OrbStack: mọi máy và container chạy trong MỘT máy ảo Linux, chung một kernel; không khuyến nghị cho
  "code that will actively try to exploit the kernel and escape the sandbox — use a full virtual
  machine with its own kernel". Nên D(b) phải là **máy ảo đầy đủ có kernel riêng** (UTM, hoặc Lima
  dùng Virtualization.framework), không phải `machine.docker.isolated`.
- **OrbStack không áp quyền macOS cho uid trong container:** uid 70 ghi được vào thư mục `700` của
  chủ máy qua bind mount (đo). Hệ quả tốt: WAL không cần `777`. Hệ quả xấu: củng cố F1 — mọi tiến
  trình trong một container được mount thư mục nào thì ghi được mọi thứ tài khoản macOS ghi được.

### Biên bản SEC3 (F-3, Lỗ 5)

| Đường ra | Kết quả |
|---|---|
| HTTP | `testcases` chỉ lưu `input_sha256`/`output_sha256` + kích thước; nội dung ở kho content-addressed. Hai đường đọc kho: `/internal/judge/testdata/{sha}` (secret + chặn header Cloudflare, `InternalJudgeHttpIT`, `InternalQuaTunnelHttpIT`) và `GET /api/v1/problems/{id}/testdata` (SETTER + chủ sở hữu **trong query**; USER 403, SETTER đề khác **404** — `VanHanhHttpIT.tai_testdata`). Không DTO nào ở `*/api` có trường input/output. `sample_testcase_contents`: khoá ngoại `(testcase_id, is_sample)` + `CHECK (is_sample)` — testcase ẩn **không thể** có hàng ở đó |
| Log | LUẬT 10 + IT log thật (F-1) |
| Thông báo lỗi | `VerdictExplainer` không in nguyên `isolateStatus` (`VerdictExplainerTest`); không factory ngoại lệ nào mang đường dẫn box hay tên tệp testcase |
| Prompt LLM | N/A — module `ai` chưa có (tuần 14–15) |

Kết luận: **0 đường rò** tại thời điểm quét. 59 endpoint (`grep -rhE '^\s*@(Get|Post|Put|Delete|Patch)Mapping'`).

### Phát hiện thêm trong lúc làm

- **CodeMirror chưa từng chạy được** — cả trên CDN gốc lẫn bản vendor: cây import kéo 4 bản
  `@codemirror/state` ("multiple instances … breaking instanceof checks"). Trang nộp bài lùi về
  `<textarea>` từ lâu, không ai biết vì `catch {}` nuốt lỗi. Nộp bài vẫn chạy (đúng thiết kế). Giờ
  `console.warn`. Sửa tận gốc cần một bundle MỘT bản state — cần một bước build JS, **chờ quyết**.
- **Base backup vật lý không chạy định kỳ**: job `dev.oj.sao-luu-goc` chưa từng được cài; bản gần
  nhất 51 giờ tuổi, `kiem-wal.sh` báo **không PITR được**. Đã cài theo hướng dẫn trong plist, kích
  chạy qua launchd (exit 0, 12M, file `600`); `kiem-wal.sh` xanh toàn bộ — R4 = 1 phút.
- **F9 · 🟡 Xác minh mật khẩu trong phiên không có trần**: `TwoFactorUseCase.tat` và đổi mật khẩu
  kiểm mật khẩu cũ mà không đếm lần sai. Cần một phiên hợp lệ (token đã bị lấy) mới khai thác; trần
  BCrypt toàn cục (~16/giây) là giới hạn duy nhất. Nên đếm như FR-AUTH-08 — chưa làm.

### Việc còn lại cho anh

| # | Việc |
|---|---|
| A1 | ✅ 2026-09-24 — `main` protected: 5 check bắt buộc (`build`, `attacks`, `trivy`, `linux/amd64`, `linux/arm64`, ghim app GitHub Actions 15368), bắt buộc qua PR (0 lượt duyệt), áp cả admin, cấm force-push/xoá nhánh. Không đòi nhánh cập nhật theo `main` (strict=false) |
| A2 | ✅ 2026-09-24 — Dependabot **alerts** (trước đó cũng TẮT) và **security updates** đã bật; `security_and_analysis.dependabot_security_updates = enabled` |
| A3 | Bật firewall macOS + stealth; tắt AirPlay Receiver |
| A4 | Đăng ký thử một tài khoản (xác nhận hostname Turnstile) |
| A5 | Dọn jar cũ `~/oj-release`, ảnh `oj-worker:truoc-cve-20260924` |
| Deploy | ✅ 2026-09-24 12:34 — `oj-api-8e78041` lên sau 5s, V15 áp dụng trong 8ms (schema 14 → 15), profile `prod` qua chốt F-7. Sao lưu ngay trước đó: `ojdb_prod/gio/oj-20260924-1234.dump`. Sau deploy: `kiem-tunnel.sh` 36/36, CSP mới không còn jsdelivr, 67/67 tệp vendor qua Cloudflare khớp SHA-256, Turnstile hiện đúng, worker nối lại sau 5s. Jar lùi: `oj-api-52727b6-20260924.jar` (chạy được trên schema 15 — `MigrationTrenDuLieuCoSanIT.v15_…`) |
| Quyết | D(b) máy ảo riêng cho worker · E3 bản sao lưu ngoài máy · bundle CodeMirror · F9 |

---

## PHẦN 8 — Rà soát lần 2 (2026-09-24, sau khi làm xong Phần 7)

Lần 1 soi **biên công khai** kỹ. Lần 2 đổi góc nhìn: đứng **bên trong container worker** — chỗ
kẻ thoát được `isolate` sẽ đứng — và hỏi nó với tới được gì. Câu trả lời lật lại một giả định
của Phần 2 ("dịch vụ nội bộ chỉ nghe loopback" là đủ).

### Phát hiện mới

#### N1 · 🟠 Từ container worker tới được mọi cổng loopback của máy Mac, mạng compose và internet

Đo bằng `/dev/tcp` từ trong `oj-worker`: **mở** hết — `host.docker.internal` :8080 (API)
:8081 (actuator) :5432 :6379 :9000/:9001 :5672/:15672 :11434 (Ollama) :20241 (metrics
cloudflared) :32222 (OrbStack) :57898 (một extension của VS Code); `192.168.97.2–5` (MinIO,
RabbitMQ, Postgres, Redis — mạng compose, dù worker ở mạng `bridge` riêng); `1.1.1.1:443`,
`github.com:443`.

OrbStack đưa `host.docker.internal` vào **loopback của macOS**. Dịch vụ nghe `127.0.0.1` không
có nghĩa là container không tới được. Không cần xác thực: Ollama (`GET /api/tags` → 200),
cổng 57898 (trả `ok`). Có mật khẩu: Postgres, Redis, MinIO, và RabbitMQ — nhưng worker đang cầm
mật khẩu RabbitMQ (N3).

**Khác F1 ở điều kiện:** F1 cần thoát `isolate` **và** thoát container. N1 chỉ cần thoát
`isolate` — một lớp. Box vẫn không có mạng (14 ca tấn công), nên ai chỉ nộp bài thì không chạm
được thứ này.

**Đề xuất (cần quyết, đo lại 14 ca tấn công + 9 ca chấm):** worker vào một mạng Docker
`internal: true`, kèm một container chuyển tiếp **đúng hai cổng** (API :8080, RabbitMQ :5672).
Hoặc gộp vào D(b): máy ảo có kernel riêng, tường lửa chặn mặc định.

#### N2 · 🟠 "Kết nối từ loopback = đến từ Cloudflare" sai trên máy này

`ClientIp.cua` chỉ tin `CF-Connecting-IP` / `X-Forwarded-For` khi `getRemoteAddr()` là
loopback. Đo: giữ một kết nối từ container tới `host.docker.internal:8080` thì `lsof` phía API
thấy `127.0.0.1:8080 ← 127.0.0.1:50652`, **tiến trình OrbStack** làm đầu kia. Mọi container
trong OrbStack, máy ảo Lima (N5), và mọi tiến trình cục bộ đều được đối xử như tunnel, và tự
khai được IP bất kỳ.

Hậu quả khi đã đứng trong một container:
- Vượt trần theo IP của FR-AUTH-08, trần đăng ký, trần API ẩn danh. Trần mã hai lớp theo tài
  khoản (V15) **vẫn giữ** — lợi ích của đợt B đúng ở chỗ này.
- Khoá đăng nhập của IP người khác: khai IP nạn nhân rồi sai 5 lần. Với đếm /64 của đợt B, một
  địa chỉ giả khoá **cả dải /64** của họ.
- Làm bẩn IP trong `login_attempts`, `audit_log` — dữ liệu điều tra sau sự cố.

**Đề xuất (cần quyết — có bước trên dashboard Cloudflare):** một Transform Rule của Cloudflare
gắn header bí mật tĩnh (ví dụ `X-OJ-Bien: <48 ký tự>`) vào mọi request qua edge. `ClientIp`
chỉ tin `CF-Connecting-IP` khi header ấy khớp (so thời gian hằng, như `InternalSecretFilter`).
Phương án khác: cloudflared → origin `unix:` socket; container không tới được socket nếu không
được mount — sạch hơn nhưng cần cấu hình connector Unix-socket cho Tomcat.

#### N3 · 🟡 RabbitMQ: một user `administrator`, quyền `.* .* .*`, dùng chung cho API và worker

`rabbitmqctl list_users` → chỉ `ojuser [administrator]`. Worker cầm đúng user này, và :15672
(management) tới được từ container (N1). Thoát `isolate` là có quyền quản trị broker: xoá
queue, đổi policy, tạo user mới để bám trụ. Không mất bài nộp — Postgres là nguồn sự thật,
reaper đẩy lại — nhưng không có lý do để worker cầm quyền quản trị.

**Đề xuất:** user riêng cho worker (chỉ `read` trên queue chấm, không tag), user API không tag
`administrator`, user quản trị riêng cho UI. Chạm cấu hình cả `oj-api` lẫn `oj-worker` —
CLAUDE.md §5.6, cần hỏi.

#### N4 · 🟡 `AuditPartitionScheduler` hỏng ở MỌI lần chạy — partition tháng cạn vào 31/12/2026

`jdbc.sql("SELECT create_audit_log_partition(...)").update()` — gọi `update()` cho một câu
`SELECT` → PgJDBC: *"A result was returned when none was expected."* Hàm đã chạy xong (autocommit)
nên tháng hiện tại không sao; nhưng vòng lặp `return` ngay ở lần lặp đầu, nên **tháng +1..+3
không bao giờ được thử**. `api.log`: 7/7 lần chạy lỗi, cả 7 đều ở `2026-09-01`. Partition hiện có
(`audit_log_2026_09`…`_12`) là do V5 tạo sẵn lúc migrate.

Từ **00:00 01/01/2027 (+07)** dòng audit rơi vào `audit_log_default`. Lượt 03:15 ngày 02/01 tạo
partition tháng 1 sẽ vỡ vì DEFAULT đã có dòng thuộc khoảng ấy — chính cảnh mà thông báo lỗi của
job này mô tả. Không mất dòng audit nào, nhưng từ đó mỗi tháng lại vỡ thêm. Và một dòng ERROR mỗi
ngày dạy người trực bỏ qua ERROR.

Vì sao test không bắt được: `AuditLogChiGhiThemIT` gọi thẳng hàm SQL bằng `execute`. Chưa có
test nào chạy `baoDamPartition()`. Quét toàn repo: đây là chỗ **duy nhất** `.update()` chạy một
câu `SELECT`.

**Đề xuất:** đổi sang `.query(...)`, thêm IT gọi `baoDamPartition()` trên Testcontainers rồi
khẳng định có partition tháng +3 (IT này đỏ với mã hiện tại). **Hạn chót: trước 31/12/2026.**

#### N5 · 🟡 Máy ảo Lima `judge` còn chạy — gắn nguyên home, sudo không mật khẩu

`limactl list` → `judge` Running (vz, 4 CPU, 4GiB, đĩa 100GiB), tạo 19/08, chạy liên tục 14
ngày. Bên trong có Ubuntu 26.04, `isolate` + `isolate-cg-keeper`, containerd rootless, một bản
clone `online-judge`. Home macOS gắn **chỉ đọc** (`virtiofs ro`), user có **sudo NOPASSWD**, không
container nào đang chạy. Hostagent chuyển tiếp UDP `*:323` (chronyd) ra **mọi giao diện** của máy
Mac — firewall đang tắt (F6). Phần 2 bỏ sót vì chỉ đếm TCP.

Không có đường nào từ internet vào đây (SSH chỉ `127.0.0.1:49435`), và không phần nào của OJ
dùng nó. Nhưng đó là một máy đọc được mọi secret mà không ai trông.

**Đề xuất:** `limactl stop judge` nếu không dùng (xoá hẳn là quyết định của anh). Nếu giữ làm
nền cho D(b) thì bỏ mount home.

#### N6 · 🟢 Người lạ tạo được 500 + stack trace theo ý muốn

- `POST /api/v1/auth/login` với `Content-Type: application/xml` → **500** `internal.error`
  (lẽ ra 415).
- `PUT /index.html` → **500** (lẽ ra 405).
- `GET /error` → **500** `{"status":999}`.

Hai lỗi đầu rơi vào nhánh `Exception.class` của `GlobalExceptionHandler`, mỗi lượt để lại
`log.error` kèm stack trace **6,5KB / 65 dòng**. Đường tĩnh không qua trần API ẩn danh.
`api.log` không xoay vòng (19MB). Không lộ gì ra response. Nhưng số 5xx do người lạ tạo ra lẫn
vào số 5xx thật.

**Đề xuất:** map `HttpMediaTypeNotSupportedException` → 415 và
`HttpRequestMethodNotSupportedException` → 405, cả hai ở mức WARN, không stack trace. Xoay vòng
`api.log`.

#### N7 · 🟢 Container worker không có trần tài nguyên, không bỏ capability thừa

`PidsLimit=<nil>`, `Memory=0`, `CapDrop=[]`: giữ nguyên bộ mặc định của Docker (`NET_RAW`,
`MKNOD`…) cộng với 4 capability đã thêm. `isolate` đã giới hạn từng box bằng cgroup, nên đây là
lớp phòng thủ thứ hai. Worker dùng chung kernel và RAM với Postgres (cùng máy ảo OrbStack).

**Đề xuất:** `--pids-limit`, `--memory`, `--cap-drop ALL` rồi thêm lại đúng những gì `isolate` cần.
Đo lại 14 + 9 ca, giống D0.

### F-6 · kết quả kiểm "đề trong hai kỳ thi chồng giờ"

**Không có đường rò.** `BI_KHOA` là một `EXISTS` trên **mọi** kỳ thi chứa đề: chỉ cần một kỳ thi
chưa mở, hoặc người xem chưa đăng ký, là đề bị khoá. Nghĩa là hệ thống hỏng theo hướng đóng.

Còn **một cách tính sai điểm:** hai kỳ thi cùng đang chạy, cùng chứa đề, A tự do, B cần đăng ký,
thí sinh đã đăng ký B. `contestDangChayChuaDe` lấy `min(id)`, nên bài nộp có thể được tính cho
A thay vì B.

Chỉ chủ đề hoặc ADMIN dựng được cấu hình này — `themDe` chỉ gắn **đề của chính mình**. Không
người ngoài nào kích hoạt được nó.

**Đề xuất:** `themDe` / `soanDeRieng` từ chối gắn một đề vào kỳ thi có khung giờ chồng lên một
kỳ thi khác đang chứa đề ấy.

### Đo lại và vẫn ổn

| Vùng | Bằng chứng lần 2 |
|---|---|
| Mã vừa viết (B) | `TotpChecker` kiểm khoá **trước** khi so mã. Bộ đếm là một câu `UPDATE … RETURNING` nguyên tử. `LoginUseCase` không `@Transactional`, nên số đếm không bị cuộn lại. `tat` có `noRollbackFor`. Chỉ 3 chỗ gọi `TotpChecker`, và `DatabaseTwoFactorGate` chỉ gọi `dangBat` |
| Phân quyền | Bài nộp: `user_id = :requesterId OR ADMIN` **trong SQL**, luồng SSE đi qua cùng đường ấy. Job: `created_by` trong SQL. Gắn đề vào kỳ thi: chỉ đề của mình. Bảng xếp hạng lúc đóng băng: luồng SSE lọc lại cho đúng người mở luồng |
| Biên công khai | `kiem-tunnel.sh` **36/36**. `.env`, `.git/config`, `application.yml`, `/v3/api-docs`, `swagger-ui`, `h2-console` → 404. `..%2f` → 400. JWT `alg:none` / HS256 giả / RS256 → 401. JSON hỏng → 400, không stack trace. `TRACE` → 405 |
| Đề bài | CommonMark `escapeHtml(true)` + `sanitizeUrls(true)`. KaTeX không bật `trust`, nên `\href{javascript:…}` / `\htmlData` không chạy |
| ZIP testdata | Đếm byte thật, có trần tỉ lệ nén và số entry. Không dùng tên entry làm đường dẫn |
| Secret | `.env` 600 · credentials tunnel 400 · `~/oj-backup` 700. Placeholder trong `.env.example` để rỗng, và thiếu hoặc ngắn hơn 32 ký tự thì crash lúc boot. Actuator :8081 chỉ mở `health` |
| CI `f6cfbb5` | `ci` đỏ: Maven Central trả **429** cho `surefire-junit-platform:3.5.6`, dù log ghi "Cache hit". Gốc: `cache: maven` cho ba workflow **chung một khoá**; `quet-phu-thuoc` (`-DskipTests`, xong trước) ghi một cache thiếu provider test, nên `ci` tải lại từ Central mỗi lượt và không bao giờ ghi bù. Đã sửa: mỗi workflow một khoá `actions/cache` riêng có `restore-keys`, retry 429 lên 6 lần, `upload-artifact@v6` (Node 24) |

### Thứ tự đề xuất

1. **N4** — có hạn chót, sửa nhỏ, có test đỏ trước.
2. **N6** — sửa nhỏ, cùng vùng `platform/error`.
3. **N2 + N1 + N3** — cùng một câu hỏi: container được nói chuyện với ai. Làm cùng D(b), cần quyết.
4. **N5, N7, F-6** — theo thời gian rảnh.
