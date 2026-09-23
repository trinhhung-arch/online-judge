# Kế hoạch kiểm thử bảo mật — Online Judge v1.0

> Tài liệu này **không đặt ra ngưỡng mới**. Mọi con số nằm ở `nfrplan.md` Phần 1 (SEC1–SEC3)
> và Phần 4; mọi ranh giới hiển thị nằm ở `oj-api/CLAUDE.md` mục 2. Đây là kế hoạch *kiểm
> chứng* chúng — thứ `nfrplan.md` 4.5 gọi tên nhưng chưa chia thành việc làm được.
>
> Viết ở M6, khi M0–M6 đã xong. Vì thế phần lớn tài liệu là **bản đồ những gì đã có**, không
> phải danh sách việc mới: một kế hoạch bảo mật viết như thể chưa có gì sẽ dẫn tới làm lại
> thứ đã chạy trong CI 400 lần, và bỏ sót đúng những chỗ chưa ai canh.

---

## PHẦN 0 — Ba nguyên tắc

**1 · Đếm, đừng tin dấu xanh.** Mẫu tốt nhất dự án này có là bước cuối của
`sandbox-attack.yml`: nó không chỉ chạy 14 ca, nó **đếm** file tấn công, **đếm** ca đã chạy,
và đỏ nếu có `skipped`. Vì `./mvnw verify` vẫn in BUILD SUCCESS khi một bộ test biến mất.
Mọi lưới mới trong tài liệu này phải có chốt đếm tương đương, nếu không nó chỉ là lời hứa.

**2 · Một lỗ hổng chỉ tồn tại khi có đường đi tới nó.** Không liệt kê rủi ro chung chung.
Mỗi mục dưới đây phải trả lời được: *đường nào, từ vai trò nào, tới dữ liệu nào.* Rủi ro không
có đường đi thì ghi vào Phần 5 ("không làm") kèm lý do, chứ không để lửng lơ.

**3 · Ba thứ hệ thống bán — công bằng, không mất bài, an toàn — xếp đúng thứ tự đó.**
Một lỗ làm lộ testcase ẩn nặng hơn một lỗ làm sập site: site sập thì thi lại được, đáp án lộ
thì kỳ thi ấy hỏng vĩnh viễn. Thứ tự ưu tiên ở Phần 3 theo đúng thang này, không theo CVSS.

---

## PHẦN 1 — Bản đồ hiện trạng: đã có lưới ở đâu

### Tầng 1 — Sandbox (SEC1) · ✅ mạnh nhất, không cần thêm gì

| Biện pháp | Lưới tự động |
|---|---|
| 14 ca tấn công | `oj-worker/src/test/resources/attacks/01..14-*.cpp` + `SandboxAttackIT` |
| Chạy mỗi push | `.github/workflows/sandbox-attack.yml`, workflow **riêng** để dấu X nói đúng vùng hỏng |
| Không biến mất được | Bước "Đủ 14 ca và không ca nào bị skip" — đếm file, đếm `tests="14"`, đòi `skipped="0"` |
| `/proc` và `/tmp` bị gỡ | `IsolateCommandTest` |
| Box sạch giữa hai bài | `SandboxAttackIT` — "file rơi rớt là rò rỉ giữa hai bài nộp" |
| Bẫy macOS | `scripts/kiem-sandbox.sh` **đếm** số ca; `./mvnw verify` trên macOS abort cả class mà vẫn xanh |

### Tầng 2 — Ứng dụng · ✅ dày, ⚠️ bốn chỗ hở (xem Phần 2)

| Nhóm | Lưới tự động |
|---|---|
| Phân quyền — tuyên bố | ArchUnit **LUẬT 8**: mọi `*UseCase` phải mang `@RequiresRole` · `@PublicAccess` · `@InternalAccess`. Không có mặc định im lặng |
| Phân quyền — hiệu lực | `AuthorizationIT`: advisor có thật được gắn · vai trò sai → `DomainException.Kind.FORBIDDEN` **và KHÔNG chạm dữ liệu** (`users.status` còn `ACTIVE`) · cổng 2FA cho ADMIN — và **không đi vòng qua `isAdmin()`** ở cả 11 use-case mức SETTER/USER (`HaiLopKhongDiVong*IT`, Lỗ 8). Đây là tầng use-case; chặng `FORBIDDEN → HTTP 403` do `GlobalExceptionHandlerTest` canh |
| IDOR | `AuthorizationIT.ChongIdor` (bài người khác → **404, không phải 403**) · `JudgingSqlInvariantsTest` truy vấn 6 · `XoaDeIT` (*"câu DELETE tự nó cũng từ chối người không sở hữu"*) — điều kiện chủ sở hữu **trong câu query**. `ProblemListContestIT` thuộc tầng công bằng kỳ thi, không phải IDOR |
| SQL injection | LUẬT 5 (ArchUnit) + `scripts/kiem-noi-chuoi-sql.sh` cho LUẬT 5d — từ Java 9 `a + b` là invokedynamic nên ArchUnit **không** thấy, script mới thấy |
| Upload ZIP | `ZipTestdataValidatorTest`: zip slip (danh sách **cho phép**, không phải danh sách cấm) · zip bomb đo **trong lúc** đọc · **không tin `ZipEntry.getSize()`** · mục lục YAML dùng danh sách trắng |
| Upload ZIP — RCE | **SnakeYAML mặc định là lỗ hổng RCE**: payload khởi tạo lớp Java bị từ chối, không được thực thi — `TestdataManifest` + `ZipTestdataValidatorTest` |
| Upload ZIP — symlink | ⚠️ **cố ý KHÔNG có phép kiểm.** Lớp này không bao giờ ghi ra hệ thống tệp — nội dung đi thẳng vào kho content-addressed — nên entry symlink chỉ là một chuỗi vô hại. Mối nguy bị loại **theo cấu trúc**. `frplan.md` vẫn liệt kê "chặn symlink"; ai đổi lớp này sang giải nén ra đĩa là làm sống lại mối nguy mà **không có test nào đỏ** |
| Cache phản hồi cá nhân | `KhongLuuDemApiFilter`: `Cache-Control: no-store` trên mọi `/api/v1/*` — cả biến thể `;x` / `%61`, cả 401/404, cả SSE; file tĩnh **không** bị áp. `KhongLuuDemApiHttpIT` · `SubmissionSseIT` · `KhongLuuDemApiFilterTest`. Gỡ `setHeader` → IT 5/6 + SSE 3/4 đỏ; đặt header SAU chain → SSE 3/4 đỏ (từ 2026-09-24) |
| Turnstile — nơi giải token | `hostname` của siteverify phải thuộc `OJ_TURNSTILE_HOSTNAMES` (so nguyên chuỗi, không so đuôi); thiếu cấu hình khi bật → **không khởi động**. `TurnstileVerifierTest` · `TurnstilePropertiesTest` (có ca đi qua `Binder` với chuỗi env). Gỡ phép đối chiếu → 3/9 đỏ (từ 2026-09-24) |
| XSS — server | `escapeHtml(true)` + `sanitizeUrls(true)` trong `CommonMarkStatementRenderer`; `CommonMarkStatementRendererTest` có **3 ca tấn công + 1 ca chống hồi quy** trên tổng 10 |
| XSS — header | `SecurityHeadersFilter` **5 header trên mọi response** + HSTS **có điều kiện** (tắt khi `hsts-max-age = 0`, và đó là mặc định localhost); CSP rỗng → **không khởi động được** |
| JWT | `JwtTest` — bộ tấn công riêng: sửa payload tự thăng ADMIN thì chữ ký hỏng · `alg=none` (hai biến thể) · đổi thuật toán **với chữ ký hợp lệ** · hạn dùng · đầu vào dị dạng. Gỡ Bước 2 của `Jwt.moKhoa()` → đỏ 2 ca (Lỗ 1) |
| Phiên | `IdentityUseCasesTest` — trình lại refresh token đã thu hồi thì **thu hồi TOÀN BỘ phiên**, kể cả phiên hợp lệ của chủ tài khoản, và ghi `REFRESH_TOKEN_REUSE_DETECTED` |
| Rate limit | `GioiHanApiFilterTest` · `GioiHanApiHttpIT` · `SubmissionRateLimitIT` · `XacMinhEmailUseCaseTest`; đếm theo `userId` khi đã đăng nhập (NAT không vạ lây), `/internal/**` không bị đếm. ⚠️ **Redis chết → CHO QUA**, có chủ ý: đây là lưới chống lạm dụng, không phải lưới chống xâm nhập |
| Bề mặt HTTP | `HttpSurfaceTest` — `/internal/**` **ngoài** `/api/v1`, chỉ ở `api.internal`; mọi controller khai `@RequestMapping` mức class |
| Đường ra testdata | `InternalJudgeHttpIT`: không secret → **401, không một byte nào**; hash dị dạng và hash không có đều 404 **cùng một câu** |
| Rò rỉ verdict (SEC3) | `VerdictExplainerTest` · `SubmissionSseIT` ("không byte nào nói test nào sai") · `SubmissionFeedbackIT` · `SubmissionResponseTest` |

### Tầng 3 — Vận hành · ⚠️ biết nhưng chưa chặn

| Biện pháp | Trạng thái |
|---|---|
| Secret từ env, crash lúc boot nếu thiếu | ✅ — `SmtpEmailSenderTest` là ví dụ mới nhất |
| Mac không mở port, chỉ `cloudflared` gọi ra | ✅ `scripts/kiem-tunnel.sh` (có biến thể đường dẫn + đo riêng luật ingress · từ 2026-09-24 đo thêm `no-store` qua Cloudflare, TLS tối thiểu, và đúng một connector — hai mục sau đã thử đỏ bằng `openssl`/`ps` giả; prod sau deploy đợt này: 36/36) · bẫy localhost trong `trien-khai-mac.sh` |
| Tunnel chạy đúng một connector, tự lên lại | ✅ `infra/launchd/dev.oj.cloudflared.plist` (từ 2026-09-23; trước đó hai tiến trình chạy tay — sửa ingress mà quên một cái là nửa số request vẫn qua luật cũ). Đo: dịch vụ lên đủ kết nối khi đã cất `cert.pem` |
| Máy chạy mã người lạ không giữ chìa quản trị tunnel | ✅ `cert.pem` (quyền tạo/xoá/đổi route trên cả tên miền) rời máy từ 2026-09-23. Đo: `cloudflared tunnel list` trên máy này báo không tìm thấy cert, connector vẫn 4 kết nối. Cần tạo/xoá tunnel thì lấy lại từ trình quản lý mật khẩu hoặc `cloudflared tunnel login`, dùng xong xoá. Tunnel load test `oj-do` xoá cùng lúc; `tunnel-do.sh` tự tạo lại mỗi lượt đo (cần cert.pem lúc ấy) |
| TLS tối thiểu ở biên Cloudflare | ✅ Minimum TLS **1.2** (dashboard → SSL/TLS → Edge Certificates, từ 2026-09-23). Đo bằng `openssl s_client`: `-tls1` và `-tls1_1` nhận `alert protocol version` trên cả hai IP biên, `-tls1_2`/`-tls1_3` bắt tay được. Lần đổi đầu KHÔNG được lưu, ô vẫn ghi "TLS 1.0 (default)" — đọc lại ô sau khi đổi, và đo từ ngoài chứ đừng tin mắt. `curl` hệ thống của macOS không đo được TLS 1.3 (exit 4 cả với cloudflare.com), phải dùng `openssl` |
| `/internal` không tới được từ internet | ✅ ba lớp: ingress không neo · 404 cho request mang header Cloudflare · secret — `InternalQuaTunnelHttpIT` · `InternalSecretFilterTest` (Lỗ 11) |
| `.secrets-dev` không vào git | ✅ `.gitignore:47`, chưa từng được track |
| `audit_log` append-only | ✅ `AuditLogChiGhiThemIT`, chạy bằng `oj_app` (V14). Trước 2026-09-23 dòng này dẫn `VanHanhHttpIT` — test ấy không kiểm append-only, và kiểm thật thì thủng qua partition (Lỗ 9) |
| Quét phụ thuộc | ✅ **có răng** từ 2026-09-24 — lần quét thật đầu tiên (`85f2f79`) tìm 11 CVE CRITICAL/HIGH có bản vá; `7d542e9` vá 10 (Tomcat 11.0.26 · bcprov 1.85.2 · amqp-client 5.34.0), lượt sau vá nốt CVE-2025-59952 (MinIO 8.6.0 + `okhttp-jvm`, có `MinioTestdataStoreIT` trên MinIO thật); cả hai đã chạy trên prod. ⚠️ Trivy quét **pom**, không quét jar — xem Lỗ 2 |

### Tầng 4 — Công bằng kỳ thi · ✅

`ContestAccessIT` (đề trước giờ mở) · `ContestProblemsIT` · `ContestStandingsIT` (freeze) ·
`FeedbackPolicyTest` · `ProblemListContestIT`. `ContestWindowQuery` là **một câu dùng ở bốn
nơi** — đúng mẫu chống lệch.

**Ai được SOẠN kỳ thi** — chủ kỳ thi hoặc ADMIN, và chỉ gắn được đề của chính mình:
`ContestChuSoHuuIT` · `AuthorContestUseCaseTest` (Lỗ 10, từ 2026-09-23 — trước đó tầng này chỉ
đo *ai được xem*).

### Tầng 5 — AI review · ⛔ chưa tồn tại

Module `ai` là tuần 14–15. 8 ca prompt injection (`nfrplan.md` 10.2, AI3) **chưa viết được**
vì chưa có gì để tấn công. Đặc tả đã đủ chi tiết để viết ngay khi có module — xem Đợt 4.

---

## PHẦN 2 — Mười một lỗ trong chính lưới kiểm thử *(6 đã sửa, 5 còn mở)*

Xếp theo *hậu quả × khả năng không ai phát hiện*, không theo độ khó sửa.

### Lỗ 1 · ✅ ĐÃ SỬA 2026-09-21 · Lưới `alg` của JWT từng không được test nào canh

`Jwt.moKhoa()` chống alg confusion và `alg=none` theo cách tốt nhất có thể: header **không bao
giờ được đọc** từ token, mà bị so nguyên văn với hằng `HEADER_B64` ở Bước 2, **trước** khi
chạm chữ ký. Cơ chế đúng. Nhưng hai ca canh nó thì không canh được gì:

- `alg_none_bi_loai` gửi `header.payload.` — chữ ký rỗng. Phép kiểm **hình dạng** ở Bước 1
  (`chamHai == token.length() - 1`) bắt nó trước, Bước 2 không bao giờ chạy.
- `doi_thuat_toan_bi_loai` khai `alg: HS512` nhưng ký bằng chuỗi `"chu-ky-bat-ky"`. Chú thích
  trong ca này viết *"Kẻ tấn công ký ĐÚNG bằng khoá thật"* — mã thì không làm thế. Bỏ Bước 2
  đi thì Bước 3 vẫn loại nó, vì chữ ký rác.

**Đo ngày 2026-09-21:** gỡ hẳn ba dòng của Bước 2 rồi chạy `JwtTest` →
`Tests run: 14, Failures: 0` · `BUILD SUCCESS`, trong đó khối `★ Chữ ký — bốn lớp CVE của các
thư viện JWT` xanh **5/5**. (Đã khôi phục `Jwt.java` ngay sau phép đo.)

Nghĩa là: ai đó dọn dẹp, thấy Bước 2 "thừa vì Bước 3 đã kiểm chữ ký rồi", xoá nó — và CI xanh.
Từ lúc ấy `alg=none` chỉ còn bị chặn bởi một phép kiểm hình dạng chưa từng được viết ra để
chặn nó. Đây đúng là nguyên tắc 1 của tài liệu này, áp vào chính nó: **dấu xanh ở đây không
chứng minh điều nó có vẻ chứng minh.**

**Đã sửa.** `JwtTest` có thêm hàm `kyThat()` ký đúng bằng khoá thật, và hai ca đi qua Bước 2:

- `doi_thuat_toan_bi_loai` — `alg: HS512`, chữ ký **hợp lệ hoàn toàn**;
- `alg_none_kem_chu_ky_hop_le` (mới) — `alg: none` nhưng **có** chữ ký, nên không bị phép kiểm
  hình dạng ở Bước 1 bắt trước. Đây đúng là biến thể mà thư viện thật từng thủng.

Đo lại cùng ngày, cùng cách: gỡ Bước 2 → `Tests run: 15, Failures: 2` · **BUILD FAILURE**, hỏng
đúng hai ca trên. Khôi phục → 15/15 xanh; toàn bộ unit test `oj-api` 412/412 xanh.

> Giữ mục này lại thay vì xoá: nó là lý do `JwtTest` phải ký thật thay vì dùng một chuỗi bất kỳ.
> Ai thấy `kyThat()` rườm rà và rút gọn lại sẽ tháo đúng cái lưới này ra lần thứ hai.

### Lỗ 2 · ✅ ĐÃ SỬA 2026-09-21 · SEC2 từng không có răng

`nfrplan.md` 4.3 viết *"Trivy hoặc Dependabot, **chặn merge** nếu có CRITICAL/HIGH"*, và §14
đòi bằng chứng *"Trivy: 0 CRITICAL/HIGH"*. Hôm nay **không có gì sinh ra được bằng chứng ấy**.
`.github/dependabot.yml` tự liệt kê ba thứ nó không làm: không chặn merge (cần branch
protection), không quét CVE (cần bật *security updates* ở Settings), không làm CI đỏ (cần
bước Trivy). Đây là mục duy nhất của `nfrplan.md` Phần 4 chỉ có lịch mở PR đứng sau.

**Đã sửa** — `.github/workflows/quet-phu-thuoc.yml`. Ba quyết định đáng ghi lại:

- **Workflow riêng, chạy song song.** Không tiêu một phút nào của ngân sách CI < 10 phút
  (nfrplan M3), nên cổng này **không** đánh đổi SLO nào. Đó là điều làm nó khác với phương án
  "thêm một bước vào `ci.yml`" mà `dependabot.yml` đã hoãn.
- **`ignore-unfixed: true`.** Một CVE chưa có bản vá thì đỏ CI không đổi được gì, và một cổng
  đỏ-mà-không-vá-được sẽ bị tắt — tắt một lần thì hiếm khi bật lại. Cổng chỉ đỏ khi **có việc
  để làm**. CVE chưa vá vẫn in ra ở bước báo cáo không-chặn.
- ~~**Quét jar đã dựng, không quét `pom.xml` suông.**~~ **Đo 2026-09-24: sai.** Ở `scan-type: fs`
  bảng tổng hợp chỉ có bốn target, cả bốn là `pom.xml` — jar trong `target/` không được đọc.
  Trivy tự giải cây phụ thuộc từ pom (kể cả bản do parent Spring Boot quản) và đã bắt đúng
  Tomcat 11.0.24 đi vào bắc cầu, nên cổng vẫn có răng; nhưng nó quét cây **suy ra**, không phải
  jar sẽ chạy. Đọc jar thật cần `scan-type: rootfs` trên `target/` — chờ người quyết.

Kèm một chốt tự canh theo khuôn `sandbox-attack.yml`: nếu ai hạ `exit-code` hoặc thu hẹp
`severity`, chính job ấy đỏ.

> **Lần chạy thật đầu tiên — 2026-09-24.** Lần đầu (`ae7e81c`) đỏ ở "Set up job" mà chưa quét
> gì: tag `aquasecurity/trivy-action@0.28.0` đã bị gỡ (repo chỉ còn `v0.x.y`). Ghim theo SHA
> (`85f2f79`). Lần quét thật tìm 11 CVE CRITICAL/HIGH có bản vá: Tomcat ×3 CRITICAL, Bouncy
> Castle ×2 CRITICAL + 1 HIGH, amqp-client ×4 HIGH, MinIO ×1 HIGH. Spring Boot 4.1.1 là bản
> 4.1.x mới nhất và chính nó ghim bản có lỗ, nên `7d542e9` ghi đè thuộc tính của parent; đã
> deploy cả API lẫn worker, broker thấy hai client 5.34.0, sandbox 14/14 + chấm thật 9/9 trên
> ảnh mới. IT **không** chạm RabbitMQ thật — bản nâng ấy được đo trên prod (broker thấy hai client
> 5.34.0). MinIO thì từ lượt vá cuối có `MinioTestdataStoreIT` (4 ca, MinIO thật; gỡ tạo bucket
> trong `luu` → 1/4 đỏ). MinIO 8.6.0 cần thêm `okhttp-jvm`: `okhttp` 5.x trên Central là gốc Kotlin
> đa nền tảng không có class JVM (767 byte), Maven không tự chọn biến thể như Gradle.
> Lần CI đầu của IT ấy đỏ: repo `minio/minio` trên **Docker Hub đã 404** ("repository does not
> exist") — máy dev xanh chỉ vì có sẵn ảnh. `docker-compose.yml` dùng cùng ảnh, nên máy mới cũng
> không dựng được MinIO. Chuyển cả hai sang `quay.io/minio/minio` + ghim digest — cùng digest
> `9535594a…` với ảnh cũ, không đổi một byte.

> **Còn lại, và KHÔNG commit vào repo được:** biến dấu đỏ thành *không merge được* là branch
> protection cho `main` (đặt `ci` · `sandbox-attack` · `quet-phu-thuoc` làm required check), và
> bật Dependabot *security updates* ở Settings > Code security. Hai việc bấm trên GitHub.

### Lỗ 3 · Bất biến #9 được canh ở `toString()`, không ở dòng log

Năm chỗ ép `toString()` không chứa bí mật (`MaXacMinhEmailTest` · `SubmissionResponseTest` ·
`SubmitSolutionUseCaseTest` · `ClaimJudgeJobUseCaseTest` · `JwtTest`). Đó chặn được
`log.info("{}", cmd)` — đường rò phổ biến nhất. Nó **không** chặn `log.info("source={}",
cmd.source())`, và **không một test nào bắt output log thật** rồi soi. Trong 12 bất biến,
#5 có script riêng, #10 và #12 có ArchUnit, #3 có test riêng — #9 là cái duy nhất chỉ có
javadoc. Lỗ này rẻ nhất trong bảy lỗ và nằm ở bất biến đắt nhất.

### Lỗ 4 · `innerHTML` duy nhất không bị ép là duy nhất

`js/problem.js:30` — `khung.innerHTML = de.statementHtml` — là chỗ **duy nhất** của cả giao
diện. Ba file JS khác mang javadoc hứa *"không một innerHTML nào"*. Đó là lời hứa, không phải
lưới: thêm cái thứ hai không làm đỏ gì cả. `BeMatFrontendTest` đã đọc và phân tích file JS
cho bốn luật khác — thêm luật này gần như miễn phí.

### Lỗ 5 · SEC3 kiểm bằng "rà soát thủ công"

Bảng SLO ghi thẳng: cách đo của SEC3 là *rà soát thủ công*. Có bốn test điểm rất tốt, nhưng
chúng canh **bốn đường đã biết**. Đường thứ năm — một trường mới thêm vào một DTO — không làm
đỏ gì. §14 đòi *"rà soát đường rò rỉ testdata: API, log, error message, prompt LLM — 0 đường"*,
tức là một lần quét **toàn bộ bề mặt**, có biên bản, không phải bốn assertion.

### Lỗ 6 · Buổi tấn công chéo tuần 9 chưa làm

README tự ghi: *"Ba việc không phải code của tuần 9 chưa làm: buổi tấn công chéo · usability
test đợt 1 · …"*. §14 đòi **biên bản**. Tuần 9 đã qua từ M5. Đây là mục rẻ nhất về công cụ
(0 dòng code) và đắt nhất về thời gian người (2 × 3h) — nên nó bị hoãn, và nên được đặt lịch
thay vì để trôi tiếp.

### Lỗ 7 · Checklist OWASP Top 10 chưa tồn tại

§14 đòi *"điền đủ, mỗi mục ghi biện pháp"*. Phần 4 dưới đây **điền sẵn 10 mục** từ những thứ
đã chạy trong CI — phần lớn chỉ là chép chứng cứ đã có. Hai mục thật sự trống.

### Lỗ 8 · ✅ ĐÃ SỬA 2026-09-23 · Cổng 2FA của ADMIN chỉ canh cửa mang nhãn ADMIN

Cổng từng nằm trong `RequiresRoleAdvisorConfig` và chỉ được hỏi khi use-case khai
`@RequiresRole(ADMIN)`. Nhưng 11 use-case mức SETTER/USER trao quyền ADMIN bằng `isAdmin()`
hoặc `:requesterRole = 'ADMIN'` — nên ADMIN **chưa bật 2FA** vẫn tải được testdata mọi đề, đọc
source mọi người, xem đề trước giờ thi và bảng đóng băng. `AuthorizationIT.CongHaiLop` chỉ thử
`AnonymizeAccountUseCase`, nên CI xanh.

**Đã sửa** (ADR 017): `JwtCurrentUserProvider` hạ ADMIN chưa bật 2FA xuống SETTER ngay khi trả
danh tính; `JwtService.doc()` thành package-private để không ai đọc được vai trò thô. Cả 11
use-case được ghim ở `HaiLopKhongDiVongIT` + `HaiLopKhongDiVongKyThiIT`. **Đo:** gỡ phép hạ vai
trò → `Tests run: 24, Failures: 13` · BUILD FAILURE; trả lại → 24/24 xanh.

### Lỗ 9 · ✅ ĐÃ SỬA 2026-09-23 · `audit_log` append-only chưa từng được kiểm — và nó thủng

Bảng ở Tầng 3 từng ghi *"`audit_log` append-only ✅ `VanHanhHttpIT`"*. `VanHanhHttpIT` chỉ kiểm
chỉ ADMIN đọc được và mỗi lần đổi công tắc sinh một dòng; bộ IT chạy bằng role sở hữu schema,
nên không test nào từng đóng vai `oj_app`. Đóng vai lần đầu thì thủng ngay: V8 chỉ `REVOKE` trên
bảng **cha**, còn dòng thật nằm ở partition, và Postgres không truyền quyền từ cha xuống con —
`DELETE FROM audit_log_2026_09` chạy được. Partition job hằng ngày tạo ra còn được
`ALTER DEFAULT PRIVILEGES` của V8 cấp sẵn DML.

**Đã sửa** — `V14__audit_log_khoa_ca_partition.sql`: `REVOKE ALL` trên mọi partition đang có, và
hàm `create_audit_log_partition` khoá partition ngay khi tạo. Ghi/đọc qua bảng cha không đổi.
`AuditLogChiGhiThemIT` dựng role bằng đúng `infra/postgres/init/01-roles.sql` rồi kết nối
**bằng `oj_app`**; chỉ SQLState `42501` mới tính là "bị chặn". **Đo:** bỏ V14 → 2/2 đỏ; chỉ bỏ
phần khoá trong hàm → đúng ca "partition tạo sau V14" đỏ; trả lại → 2/2 xanh.

> **Đã deploy 2026-09-23 19:47** (Flyway v12 → v14 trên `ojdb_prod`). Kiểm ngay sau deploy:
> `has_table_privilege('oj_app', <partition>, 'DELETE' | 'UPDATE')` ra `f` trên cả năm partition
> đang có (`audit_log_2026_09` … `audit_log_default`).

### Lỗ 10 · ✅ ĐÃ SỬA 2026-09-23 · SETTER bất kỳ sửa được kỳ thi của người khác

`AuthorContestUseCase` chỉ có `@RequiresRole(SETTER)` — cái sàn — và câu SQL gắn/gỡ đề không
nhận người gọi. Ba đường, đều chạm công bằng kỳ thi:
- gỡ, đổi nhãn, đổi điểm đề trong kỳ thi sắp diễn ra của người khác;
- gắn đề **công khai** của người khác vào một kỳ thi tự tạo kéo tới 2099 — đề biến khỏi kho
  với mọi người, chủ đề không sửa được, và không bao giờ xoá được nữa;
- gắn lần lượt từng id vào kỳ thi của mình rồi đọc trang kỳ thi — lộ mã của đề DRAFT và đề
  của kỳ thi chưa mở.

Tầng 4 ở trên từng ✅ mà không có dòng nào về *ai được soạn* kỳ thi — cả năm test đều đo
*ai được xem*.

**Đã sửa**: port mới `ContestAuthoringRepository` — mọi câu ghi `contest_problems` mang người
gọi vào tận SQL: sửa kỳ thi cần `created_by = người gọi`, gắn đề cần thêm `owner_id = người
gọi` (ADMIN thì qua cả hai). Đề của người khác và id không có thật trả **cùng một câu**.
`ContestChuSoHuuIT` (7 ca, có HTTP thật) + `AuthorContestUseCaseTest` (5 ca). **Đo:** gỡ ba điều
kiện SQL → 6/7 IT đỏ (ca còn xanh là ca đối chứng "chủ kỳ thi vẫn soạn được"); cho use-case
luôn truyền `laAdmin = true` → 5/5 unit đỏ.

> **Hệ quả người dùng thấy:** kỳ thi nhiều người ra đề thì ADMIN ghép đề, hoặc chủ kỳ thi soạn
> đề ngay trong kỳ thi (V10). SETTER không còn mượn được đề của SETTER khác.

### Lỗ 11 · ✅ ĐÃ SỬA 2026-09-23 · Lớp mạng của `/internal` là một regex, và nó thủng

Luật ingress `^/internal(/|$)` không khớp `//internal/…`, `/./internal/…`,
`/api/v1/../../internal/…`, `/internal;x=1/…`, `/%2e/internal/…`,
`/api/v1/%2e%2e/%2e%2e/internal/…` — đo bằng `cloudflared tunnel ingress rule` (2026.9.0): cả
sáu đi thẳng tới `localhost:8080`. Tomcat 11.0.24 chuẩn hoá cả sáu về `/internal/judge/*` (còn
`%3B` thì không), nên chúng tới `InternalSecretFilter` — chỉ còn
shared secret đứng chắn. `kiem-tunnel.sh` chỉ thử đường dẫn chuẩn, nên nó báo ✓ trên đúng cấu
hình thủng ấy.

**Đã sửa**, ba mảnh, không mảnh nào chạm `oj-worker` hay `oj-contract`:
1. **Lớp 2 mới, trong mã** — `InternalSecretFilter` trả 404 cho request mang `CF-Ray` /
   `CF-Connecting-IP` / `CDN-Loop`, **trước** bước kiểm secret. Container đã chuẩn hoá đường
   dẫn trước khi gọi filter, nên lớp này không phụ thuộc chuỗi. Lộ secret không còn đủ để ghi
   verdict từ internet.
2. **Luật ingress không neo** — `(^|/)internal(;[^/]*)?/`, tương tự cho `actuator`. Chặn đủ sáu
   biến thể, và vẫn để lọt `/api/v1/contests/internal` (kỳ thi tên `internal`).
3. **`kiem-tunnel.sh`** thử các biến thể bằng `curl --path-as-is`, và **đo riêng lớp 1** bằng
   `cloudflared tunnel ingress rule`. Nhìn từ ngoài, 404 không nói được lớp nào đã chặn, nên
   thiếu mục này thì lớp 1 hỏng không ai hay. Chạy thử trên cấu hình cũ: 8 ô hỏng; cấu hình
   mới: 0.

**Đo:** `InternalQuaTunnelHttpIT` gửi đường dẫn nguyên văn qua Tomcat thật. Mỗi biến thể phải
ra 401 khi không có dấu Cloudflare — chứng minh nó tới được filter, không thì ca kia xanh vô
nghĩa — rồi 404 khi có dấu Cloudflare và đúng secret. Gỡ phép kiểm → IT 2/3 đỏ (ca đối chứng
"worker vẫn qua" xanh), unit 2/7 đỏ.

> **Đã làm trên host 2026-09-23.** Trước khi sửa, đo trên site thật: `//internal/judge/claim`,
> `/./internal/…`, `/internal;x=1/…`, `/%2e/internal/…` đều ra **401** — Cloudflare KHÔNG chuẩn
> hoá đường dẫn trước khi chuyển về tunnel, nên lỗ có hiệu lực thật. Đã chép hai luật mới vào
> `~/.cloudflared/config.yml` (bản cũ: `config.yml.bak-20260923`), thay hai connector cũ bằng một
> connector mới không gián đoạn, deploy oj-api có chốt tầng 2. Sau đó: cả sáu biến thể ra 404,
> `POST /internal/judge/claim` kèm `CF-Ray` gọi thẳng localhost ra 404, `kiem-tunnel.sh` 33/33.

> **Bỏ sót, vá cùng ngày.** Còn một chỗ sinh luật ingress thứ hai: `scripts/tai-trong/tunnel-do.sh`
> (tunnel `oj-do` của load test) vẫn viết luật neo cũ. Đo lại cấu hình nó sinh: `//internal/…`,
> `/internal;x=1/…`, `//actuator/…` đi thẳng tới 18080. Nó còn gọi `kiem-tunnel.sh` cho tên miền
> đo mà không đặt `CLOUDFLARED_CONFIG`, nên mục 2b đọc config **prod**. Tên miền đo không có trong
> config prod nên rơi vào luật bắt-tất-cả 404, và cả 11 dòng của mục 2b xanh mà không đo gì.
> Đã vá: script sinh đúng hai luật của prod, truyền `CLOUDFLARED_CONFIG`, và mục 2b giờ tự kiểm
> trước là file cấu hình có phục vụ tên miền ấy không. Đo mục 2b trên bốn cặp tên miền × cấu hình:
> tên miền đo × prod thì đỏ ở dòng kiểm đầu; tên miền đo × luật cũ thì hỏng 9; tên miền đo × luật
> mới thì 0; prod × prod thì 0.

---

## PHẦN 3 — Kế hoạch: bốn đợt

### Đợt 1 — Bịt ba lỗ trong lưới tự động *(ưu tiên cao nhất, ~1 ngày)*

| # | Việc | Bằng chứng khi xong |
|---|---|---|
| 1.1 | **LUẬT 10** trong `CodingRulesTest` hoặc `scripts/kiem-log-bi-mat.sh`: cấm truyền `.source()` · `.password()` · `.token()` · `.rawCode()` · nội dung testcase vào một lời gọi logger | luật đỏ khi cố tình thêm một dòng log vi phạm |
| 1.2 | Một IT bắt log thật (`ListAppender` vào logger gốc) chạy trọn đường **nộp bài → verdict** với source mang chuỗi mốc, rồi khẳng định chuỗi ấy không xuất hiện ở bất kỳ dòng nào | IT đỏ nếu ai thêm `log.debug` vào đường nóng |
| 1.3 | Luật mới trong `BeMatFrontendTest`: **đúng một** `innerHTML` trong `static/**`, ở `problem.js`, gán từ `statementHtml` | đỏ khi có cái thứ hai — kể cả `insertAdjacentHTML` |
| ~~1.4~~ | ✅ **XONG 2026-09-21** — `kyThat()` + hai ca chạm Bước 2 | gỡ Bước 2 → `Tests run: 15, Failures: 2`, BUILD FAILURE. Trước đó: xanh 14/14 |
| 1.5 | Chốt **đếm** cho các luật trên, theo mẫu `sandbox-attack.yml` | xoá một luật → CI đỏ, không phải xanh |

> 1.1 và 1.3 đều là *luật về hình dạng code*, nên chúng thuộc `CodingRulesTest` /
> `BeMatFrontendTest` chứ không phải một bộ test bảo mật riêng. Dự án này chưa bao giờ có
> "thư mục security tests", và không nên bắt đầu: một luật nằm cạnh các luật khác thì được
> đọc, nằm riêng thì bị quên.

### Đợt 2 — Một lần quét SEC3 toàn bề mặt *(~nửa ngày, ra biên bản)*

Không phải test — là **rà soát có phương pháp**, kết quả là một bảng ký được cho §14.
Bốn đường ra, rà theo đúng thứ tự này:

1. **HTTP response** — liệt kê mọi DTO trả ra từ mọi mapping, đánh dấu trường nào bắt nguồn
   từ `testcases.input/output`. Kỳ vọng: **0**. Đường hợp pháp duy nhất là
   `/internal/judge/testdata/{sha256}` sau shared secret. Lấy danh sách bằng lệnh, đừng chép
   số vào đây — mỗi con số chép tay là một chỗ để tài liệu mục ruỗng:
   ```
   grep -rn --include='*.java' -E '^\s*@(Get|Post|Put|Delete|Patch)Mapping' oj-api/src/main/java
   ```
2. **Log** — sau Đợt 1 thì đây chỉ là xác nhận lại.
3. **Error message** — `GlobalExceptionHandler` + mọi `*Exception` factory: câu chữ có bao giờ
   chứa đường dẫn hệ thống, tên file testcase, hay `isolateStatus` nguyên văn không.
4. **Prompt LLM** — chưa có module, ghi "N/A tuần 14–15" thay vì để trống.

### Đợt 3 — Buổi tấn công chéo *(2 người × 3h, đặt lịch cụ thể)*

Mỗi người tấn công vùng **người kia** viết. Trang đầu phải đọc là bảng ma trận hiển thị ở
`oj-api/CLAUDE.md` mục 2 — mục tiêu là tìm **ô nào của bảng ấy sai trong thực tế**.

Mười hai kịch bản mở màn, mỗi cái là một ô của ma trận hoặc một bất biến:

| # | Kịch bản | Ô / bất biến |
|---|---|---|
| 1 | Là tác giả bài nộp, lấy nội dung testcase ẩn mà bài mình vừa fail | ô quan trọng nhất · #1 |
| 2 | Đoán `sha256` của `/internal/judge/testdata` từ bất cứ thông tin công khai nào | #1 |
| 3 | Gọi `/internal/**` qua tunnel thay vì mạng nội bộ — thử cả `//internal`, `/./internal`, `/internal;x=1`, mã hoá `%2e` | #3 · Lỗ 11 |
| 4 | Là USER, đọc bài nộp người khác **trong** contest đang chạy | ma trận, dòng "Source người khác" |
| 5 | Là SETTER, đọc bài nộp trong contest của người khác | ma trận — SETTER bị **❌**, dễ nhầm thành ✅ |
| 6 | Xem đề contest trước giờ mở bằng API trực tiếp (bỏ qua UI) | 4.4 |
| 7 | Là ADMIN chưa bật 2FA, dùng quyền ADMIN | cổng hai lớp |
| 8 | Dùng refresh token đã thu hồi, hoặc token của phiên đã logout | 4.2 |
| 9 | Leo quyền bằng cách tự ký JWT (`alg: none`, đổi `alg`, đổi `role`) | `JwtTest` mở rộng ra HTTP thật |
| 10 | Vượt rate limit bằng đổi `X-Forwarded-For` | `ClientIp` — *"biện pháp chống tấn công tự biến thành công cụ tấn công"* |
| 11 | Nhồi XSS qua đề bài Markdown cho tới khi qua được `escapeHtml` | Lỗ 4 |
| 12 | Làm đầy đĩa hoặc treo worker bằng gói ZIP testdata hợp lệ về hình thức | `ZipTestdataValidatorTest` mở rộng |

**Biên bản phải ghi cả ca thất bại.** Một danh sách 12 dòng "không phá được" là bằng chứng
mạnh hơn hai dòng lỗi tìm được — và nó nói cho người đọc sau biết vùng nào **đã** bị soi.

### Đợt 4 — Khi module `ai` ra đời *(tuần 14–15, chưa tới)*

8 ca prompt injection theo `nfrplan.md` 10.2, cộng một chốt đếm như `sandbox-attack.yml`:
bộ 8 ca không được phép biến mất im lặng. Hai luật phải có **trước** ca test đầu tiên:

- prompt **chỉ** chứa đề công khai + source của chính user + verdict + số thứ tự test fail;
- source vào prompt qua delimiter, không bao giờ nối thẳng vào phần chỉ thị.

---

## PHẦN 4 — Checklist OWASP Top 10 (2021) — bản điền sẵn để ký ở tuần 13

| Mục | Biện pháp trong dự án này | Bằng chứng |
|---|---|---|
| **A01** Broken Access Control | Kiểm ở use-case (#11), điều kiện chủ sở hữu **trong query** | LUẬT 8 · `AuthorizationIT` · `JudgingSqlInvariantsTest` q6 · `XoaDeIT` |
| **A02** Cryptographic Failures | BCrypt cost 12 · SHA-256 cho refresh token và mã xác minh · AES-GCM cho bí mật TOTP · JWT 15 phút · HSTS | `BCryptPasswordHasherTest` · `MaXacMinhEmailTest` · `AesGcmSecretCipher` · `SecurityHeadersFilterTest` |
| **A03** Injection | SQL: named parameter, cấm nối chuỗi. XSS: `escapeHtml` + `sanitizeUrls` + CSP | LUẬT 5 · `kiem-noi-chuoi-sql.sh` · `CommonMarkStatementRendererTest` · ⚠️ **Lỗ 4** |
| **A04** Insecure Design | 12 bất biến · sandbox thay vì tin tưởng · 16 ADR ghi lý do từ chối | `docs/adr/` |
| **A05** Security Misconfiguration | 5 header + HSTS có điều kiện · CSP rỗng thì crash · secret thiếu thì crash · `/internal` ngoài `/api/v1` | `SecurityHeadersFilterTest` · `HttpSurfaceTest` · `SmtpEmailSenderTest` · ⚠️ `.env.example` **không nhắc** `OJ_HSTS_MAX_AGE`, mà mặc định là `0s` = tắt — máy triển khai mới im lặng không có HSTS; chỉ `kiem-tunnel.sh` bắt được, và chỉ khi ai đó chạy nó |
| **A06** Vulnerable Components | Dependabot mở PR hằng tuần **+ Trivy làm CI đỏ** khi có CRITICAL/HIGH đã có bản vá | `quet-phu-thuoc.yml` · ⚠️ còn branch protection + công tắc security updates, xem Lỗ 2 |
| **A07** Auth Failures | header `alg` so với hằng số, **có test canh** từ 2026-09-21 (Lỗ 1) · refresh reuse → thu hồi toàn bộ · 2FA cho ADMIN · rate limit login theo IP · Turnstile (kèm đối chiếu hostname) | `JwtTest` · `IdentityUseCasesTest` · `AuthorizationIT.CongHaiLop` · `HaiLopKhongDiVong*IT` (Lỗ 8) · `TurnstileVerifierTest` |
| **A08** Integrity Failures | Ảnh worker dựng trên runner gốc từng kiến trúc; **chưa ký ảnh, chưa có SBOM** | `buildx.yml` · xem Phần 5 |
| **A09** Logging & Monitoring | `audit_log` append-only · traceId xuyên API→queue→worker · cấm `System.out` | `AuditLogChiGhiThemIT` (V14, Lỗ 9) · LUẬT 6 · ⚠️ **Lỗ 3** |
| **A10** SSRF | Không endpoint nào nhận URL từ người dùng. Ba đích gọi ra đều cố định trong config: Turnstile · SMTP · (LLM, chưa có) | cần một lượt xác nhận trong Đợt 2 |

---

## PHẦN 5 — Không làm, và vì sao

- **Pentest chuyên nghiệp** — `nfrplan.md` Phần 12 đã từ chối. Thay bằng checklist + tấn công chéo.
- **Ký ảnh container / SBOM (A08)** — có giá trị khi ảnh được phân phối cho người khác. Ở đây
  ảnh chạy trên đúng một máy, do đúng hai người dựng. Ghi là **rủi ro được thừa nhận**, không
  phải mục bị bỏ quên.
- **WAF / IDS** — Cloudflare Tunnel đã đứng trước, và Mac không mở port nào.
- **Tự viết fuzzer cho sandbox** — `nfrplan.md` 4.1 cảnh báo mạnh nhất cả dự án là *tự viết
  sandbox*. Tự viết fuzzer cho nó là biến thể mềm hơn của cùng cái bẫy.

---

## PHẦN 6 — Cần người quyết trước khi làm

1. ✅ ~~Trivy~~ — đã làm 2026-09-21 theo phương án (a), xem Lỗ 2. Còn **hai việc bấm trên
   GitHub** mà không ai commit hộ được: branch protection cho `main`, và công tắc Dependabot
   *security updates* ở Settings > Code security.
2. **Ngày cho buổi tấn công chéo** — cần hai người cùng rảnh 3h. Kịch bản đã sẵn ở Đợt 3.
