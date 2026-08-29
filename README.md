# online-judge

Hệ thống chấm bài lập trình tự động. Người lạ nộp mã nguồn, hệ thống **biên dịch và chạy mã đó
trên máy chủ**, so kết quả với đáp án, trả về verdict.

Ba thứ hệ thống này bán, xếp theo thứ tự không thể thoả hiệp: **tính công bằng** · **không mất
bài nộp** · **an toàn**. Mọi quyết định trong repo phục vụ ba điều đó; tốc độ và trải nghiệm
đứng sau. Luật đầy đủ ở [`CLAUDE.md`](CLAUDE.md), và nó thắng mọi thói quen chung về "code sạch".

| Tài liệu | Nội dung |
|---|---|
| [`docs/build-order.md`](docs/build-order.md) | thứ tự viết code, M0 → M6 |
| [`docs/nfrplan.md`](docs/nfrplan.md) | SLO, bảo mật, độ tin cậy (mã P/S/SEC/R/U/A/M/C/AI) |
| [`docs/frplan.md`](docs/frplan.md) | chức năng (mã FR-*) |
| [`docs/postgres-design.md`](docs/postgres-design.md) | schema |
| [`docs/cau-truc-source.md`](docs/cau-truc-source.md) | file nào ở đâu |
| [`docs/adr/`](docs/adr/) | quyết định kiến trúc và lý do |

---

## Chạy thử

```bash
docker compose up -d                      # postgres · redis · rabbitmq · minio
cp .env.example .env                      # điền HAI secret, mỗi cái >= 32 ký tự:
                                          #   OJ_INTERNAL_SHARED_SECRET  (worker gọi /internal)
                                          #   OJ_JWT_SECRET              (ký access token)
                                          # Thiếu cái nào thì API KHÔNG khởi động — cố ý.
./mvnw verify                             # phải xanh trước khi làm bất cứ gì khác
./mvnw -pl oj-api spring-boot:run
```

Worker cần `isolate` trên máy Linux. Cài một lần:

```bash
sudo ./scripts/build-isolate.sh           # build TỪ NGUỒN, không copy binary giữa hai máy
sudo ./scripts/build-pch.sh "-std=gnu++20 -O2"   # tuỳ chọn: biên dịch C++ nhanh gấp ~3,7 lần
sudo ./scripts/mount-box-tmpfs.sh         # tuỳ chọn, chỉ trên máy nhiều RAM
./mvnw -pl oj-worker spring-boot:run
```

### Giao diện

Sau `spring-boot:run`, mở **http://localhost:8080** — giao diện là trang tĩnh nằm trong
`oj-api/src/main/resources/static/`, không có build step và không có Node trong CI. Bốn trang:

| Trang | Nội dung |
|---|---|
| `/` | danh sách đề, lọc theo tag và theo "đã giải", phân trang cursor |
| `/problem.html?code=…` | đề bài (Markdown render server-side, KaTeX vẽ ở trình duyệt) + CodeMirror 6 + nộp bài |
| `/submission.html?id=…` | chi tiết bài nộp, cập nhật realtime qua SSE, **fallback polling 3 giây** |
| `/login.html` | đăng nhập và đăng ký |

Nháp mã nguồn nằm trong `localStorage`, khoá theo *(đề, ngôn ngữ)* — đổi ngôn ngữ không xoá
mất bản đang viết dở. Nháp **cố ý không gửi lên server**: đó là lời giải chưa nộp, và một
bảng chứa nó là một bảng ADMIN đọc được giữa kỳ thi.

CDN chết thì trình soạn mã hạ xuống `<textarea>` thường — mất tô màu cú pháp, **giữ nguyên
khả năng nộp bài**.

### Đăng nhập — từ M4 thì mọi endpoint đều cần token

Cửa hậu `FixedDevUserProvider` của M1 (mọi request chạy dưới danh nghĩa `users.id=1`) **đã bị
xoá** ở Bước 4.5. Profile `dev` seed sẵn ba tài khoản, cùng mật khẩu `matkhau-dev-123`:

| handle | vai trò | dùng để |
|---|---|---|
| `dev` | USER | nộp bài, xem bài của mình |
| `setter` | SETTER | chủ đề `A-PLUS-B` |
| `admin` | ADMIN | endpoint quản trị |

```bash
TOK=$(curl -s -X POST localhost:8080/api/v1/auth/login \
        -H 'Content-Type: application/json' \
        -d '{"dinhDanh":"dev","password":"matkhau-dev-123"}' | jq -r .accessToken)

curl localhost:8080/api/v1/me -H "Authorization: Bearer $TOK"
curl -X POST localhost:8080/api/v1/submissions -H "Authorization: Bearer $TOK" \
     -H 'Content-Type: application/json' \
     -d '{"problemId":1,"languageCode":"cpp20","source":"int main(){}"}'
```

Access token sống 15 phút; hết hạn thì `POST /api/v1/auth/refresh` với `refreshToken`. Token cũ
bị **thu hồi ngay khi làm mới** — trình lại một token đã thu hồi là dấu hiệu token bị sao chép,
và hệ thống thu hồi toàn bộ phiên của tài khoản đó (xem `RefreshSessionUseCase`).

⚠️ Ba tài khoản trên chỉ tồn tại khi `--spring.profiles.active=dev`. Trên host thì
`db/dev-seed/` không nằm trong `spring.flyway.locations`.

### Precompiled header — tuỳ chọn, nhưng đo được

Gần như mọi bài C++ mở đầu bằng `#include <bits/stdc++.h>`. `scripts/build-pch.sh` biên dịch
sẵn header đó một lần cho mỗi host; worker gắn nó **read-only vào box lúc biên dịch** (không
phải lúc chạy). Đo trong box trên WSL x86:

| | thời gian biên dịch | bộ nhớ biên dịch |
|---|---|---|
| không PCH | 2,70 s | 233 MB |
| có PCH | **0,73 s** | **87 MB** |

Bỏ qua bước này thì hệ thống vẫn chấm **đúng**, chỉ chậm hơn: thư mục không tồn tại, isolate
bỏ qua quy tắc mount (`:maybe`), GCC bỏ qua `-I` — không có lỗi nào được ném.

> ⚠️ Cờ truyền cho script phải **khớp chính xác** phần cờ trong `languages.compile_command`.
> Lệch một cờ thì GCC bỏ qua `.gch` trong **im lặng** — không cảnh báo, chỉ là chậm như cũ.
> Kiểm bằng `g++ ... -I/opt/oj/pch -H -fsyntax-only bai.cpp`: dòng đầu phải có dấu `!`.

### Sandbox không chạy được?

`make install` của isolate **không** tạo user `isolate` và **không** ghi `/etc/subuid`. Ba lỗi
dưới đây đều đã gặp thật; `scripts/build-isolate.sh` giờ lo cả ba, nhưng nếu bạn cài tay thì
đây là chỗ tra:

| Triệu chứng | Nguyên nhân |
|---|---|
| `isolate.c:17: fatal error: seccomp.h` | thiếu `libseccomp-dev` — Makefile của isolate 2.6 có `LIBS=-lcap -lseccomp` |
| `User isolate not found in /etc/subuid` **và** `Job for isolate.service failed` | một nguyên nhân, hai chỗ hỏng: config mặc định có `subid_user = isolate`, và cả `isolate` lẫn `isolate-cg-keeper` chết ở `cf_parse()` nếu thiếu user + dải subuid |
| `Cannot write .../box-N/memory.max: No such file` | `isolate-cg-keeper` chưa chạy, nên `/run/isolate/cgroup` không tồn tại. `systemctl status isolate.service` |
| `Cannot write to .../cgroup.subtree_control: Device or resource busy` | cg-keeper đang ở một cgroup còn tiến trình khác. Nó phải nằm riêng trong `isolate.slice` với `Delegate=true` — đó chính là việc của `isolate.service` |

> Trên macOS thì `isolate` không chạy được — nó dùng cgroup v2 và namespace của Linux. Máy chấm
> thật chạy Linux trong VM/container; xem [`infra/isolate/Dockerfile`](infra/isolate/Dockerfile).
> Các test cần sandbox tự bỏ qua trên macOS, và **fail** trên Linux nếu thiếu `isolate` — cố ý,
> vì một cái skip lặng lẽ biến cổng chuyển của M2 thành một lời hứa.

---

## Trạng thái

| Mốc | Nội dung | Trạng thái |
|---|---|---|
| M0 | hạ tầng, schema, hợp đồng | xong |
| M1 | vòng nộp bài → verdict, **không thực thi mã người dùng** | xong |
| **M2** | **sandbox `isolate` + 14 test tấn công** | **xong** (xem dưới) |
| M3 | realtime (SSE + Redis), subtask, feedback level | xong |
| **M4** | auth, quyền, upload đề, MinIO, giao diện | **xong 4.1–4.12** (xem dưới) |
| M5 | contest, bảng xếp hạng | chưa |
| M6 | RabbitMQ, giám sát, deploy | chưa |

```
./mvnw verify   →   424 test xanh, ~2 phút
                    oj-api     237 unit + 96 IT (Postgres 16 + Redis 7 thật, Testcontainers)
                    oj-worker   65 unit + 26 IT (isolate thật: 14 tấn công + 9 đường chấm + 3 benchmark)
```

### M4 — toàn bộ 12 bước

| Bước | Nội dung | Bằng chứng |
|---|---|---|
| 4.1 | migration **V5** — `refresh_tokens` · `login_attempts` · `login_lockouts` · `audit_log` phân mảnh theo tháng | chạy trên DB rỗng (Testcontainers) **và** DB dev đã có dữ liệu: 387ms |
| 4.2–4.4 | `identity` đầy đủ: domain thuần · 8 use-case · BCrypt cost 12 · refresh token lưu **SHA-256** | `IdentityDomainTest` 14 · `IdentityUseCasesTest` 22 |
| 4.5 | JWT HS256 **không thêm dependency** · `JwtAuthFilter` · `JwtCurrentUserProvider` thay `FixedDevUserProvider` | `JwtTest` 14 ca, gồm 4 lớp CVE của thư viện JWT · [ADR 012](docs/adr/012-tu-viet-jwt-hs256-thay-vi-them-thu-vien.md) |
| 4.6 | `@RequiresRole` + advisor ở tầng use-case · **LUẬT 8** của ArchUnit | `AuthorizationIT` — gồm một ca hỏi thẳng Spring xem advisor có được gắn không |
| 4.7 | khoá đăng nhập 5 lần/phút/IP (FR-AUTH-08) · rate limit nộp bài 1 bài/10s/user (FR-SUB-08), Redis là đường chính và Postgres là đường dự phòng | `SessionLifecycleHttpIT` · `SubmissionRateLimitIT` — cả hai đường, 429 kèm `Retry-After` |
| 4.8 | rà IDOR: bài nộp của người khác trả **404**, vai trò sai trả **403 chứ không phải 200 rỗng** | `AuthorizationIT` · `IdentityHttpIT` |
| — | migration **V6** + khung job nền (`platform/jobs`) — kéo từ M6 lên tuần 7 theo phương án (a) | `JobsIT` 11 — claim, lease, thu hồi job treo, một job mỗi loại |
| 4.9 | `problems` đầy đủ: tạo/sửa/xuất bản · `feedback_level` · danh sách phân trang · Markdown render server-side + cache `rendered_statements` | `CommonMarkStatementRendererTest` 10 · `ProblemAuthoringIT` 13 |
| 4.10 | `ZipTestdataValidator` + đánh dấu sample/hidden → **job nền có tiến độ** | `ZipTestdataValidatorTest` 17 · `TestdataImportIT` 6 |
| 4.11 | `MinioTestdataStore` — content-addressed | kiểm tay: 7 đối tượng trong `oj-testdata`, khoá là sha256 |
| 4.12 | giao diện: CodeMirror 6 · nháp localStorage · trang bài nộp SSE · a11y mức A · mobile | `GiaoDienIT` 5 |

Rate limit nộp bài là chặng **mới** duy nhất thêm vào đường nóng ở mốc này. Đo lại sau khi
thêm: `p50=9ms · p95=15ms` trên 100 mẫu — ngân sách P2 là 300ms, nên nó không lấy của ai.

**Ba việc không phải code của tuần 9 chưa làm:** buổi tấn công chéo · usability test đợt 1 ·
Cloudflare Tunnel + domain.

Ba tệp KaTeX nạp từ jsDelivr đều đã ghim bằng **SRI sha384**, hash tính từ chính tệp tải về
chứ không chép từ tài liệu. `TaiNguyenNgoaiTest` giữ điều đó: thêm một `<script>` hay `<link>`
từ CDN mà quên `integrity=` (hoặc quên `crossorigin=`, thứ mà thiếu nó thì trình duyệt bỏ qua
`integrity` **trong im lặng**) là test đỏ, kèm đúng tên tệp và URL.

### Ba lỗi chỉ hiện ra khi chạy thật

Không một test nào bắt được chúng trước khi hệ thống được khởi động và gọi bằng tay:

| Lỗi | Vì sao test không thấy | Đã chốt lại bằng |
|---|---|---|
| SSE trả **500 thân rỗng** thay vì 401/404 khi lỗi | chỉ xảy ra khi client gửi `Accept: text/event-stream`, tức là đúng cách trình duyệt gọi và không phải cách `curl` mặc định gọi | `SubmissionSseIT` — mọi phản hồi lỗi luôn là JSON có `code` |
| Bucket MinIO chỉ tạo lúc khởi động | docker-compose không bảo đảm MinIO lên trước API; `@PostConstruct` hỏng một lần rồi thôi, nạp testdata hỏng **vĩnh viễn** tới lần restart | `MinioTestdataStore.luu` tự bảo đảm bucket, có cờ để chi phí thường trực bằng 0 |
| Danh sách đề **500** khi không lọc gì | `:cursor IS NULL` với tham số NULL trần — Postgres không suy được kiểu. Đường đi mặc định hỏng, đường có bộ lọc thì chạy | `ProblemAuthoringIT` + `CAST(:x AS kiểu)` quanh mọi tham số tuỳ chọn |

> **Ba lập trường phân quyền, và không có lập trường thứ tư.** Mọi class `*UseCase` phải mang
> `@RequiresRole`, `@PublicAccess` hoặc `@InternalAccess`; LUẬT 8 fail CI nếu thiếu. Nhờ đó
> `grep -rn "@PublicAccess" oj-api/src/main` liệt kê **đủ mọi lối vào không cần đăng nhập** của
> cả hệ thống — trang đầu tiên phải đọc trong buổi tấn công chéo tuần 9.

---

## Baseline sandbox — Bước 2.10

⚠️ **Số dưới đây KHÔNG phải số của máy chấm chuẩn.** Chúng đo trên máy dev WSL2 x86, và
`nfrplan.md` 9.1 nói rõ: *một con số thời gian không kèm tên máy là một con số vô nghĩa*.
Chúng dùng để **kiểm đúng/sai**, không dùng để đặt giới hạn thời gian cho đề.

Máy chấm chuẩn là `mac-m1max-host` (arm64, 6 slot, `host_factor = 1.000` theo định nghĩa) —
**chưa được deploy**, nên cột phải còn trống. Deploy xong thì chạy lại cùng phép đo, điền vào,
và đặt `OJ_HOST_REFERENCE_CPU_MS` bằng con số ở dòng "tải chuẩn".

| Phép đo | Máy dev (WSL2, i7-9850H, 12 luồng, 7GB) | Máy chấm chuẩn (M1 Max, chưa đo) |
|---|---|---|
| `isolate` | 2.6, cgroup v2, subuid | |
| **Tải chuẩn `HostBenchmark`** (trung vị 5 lần) | **630 ms CPU** | |
| Biên dịch A+B (`bits/stdc++.h`, `-O2 -static`) | 2,83 s CPU · đỉnh 229 MB | |
| Chạy A+B | ~3 ms CPU · 1,6 MB | |
| `--cleanup` + `--init` một box | ~5 ms | |
| 14 test tấn công, cả bộ | 12,7 s | |
| 9 ca đường chấm thật | 14,6 s | |

**Cách đo lại trên một máy bất kỳ:**

```bash
./mvnw -pl oj-contract,oj-worker -am verify        # 14/14 phải xanh trước đã
./mvnw -pl oj-worker spring-boot:run               # log dòng "Đo máy ... ms CPU"
```

`HostBenchmark` gửi mỗi phép đo về `POST /internal/judge/benchmark`, nên lịch sử hiệu chuẩn
nằm trong bảng `host_benchmarks` chứ không chỉ trong log. Nó cũng chạy lại mỗi 15 phút và
**cảnh báo khi lệch quá 8%** so với lần đo đầu tiên của chính máy đó. Đó không phải chuyện hiệu chuẩn — đó là bẫy throttle nhiệt: máy chạy
90 phút contest nóng dần và chậm dần, bài nộp cuối giờ bị TLE oan, và không ai trong phòng
thi nhận ra (`nfrplan.md` Phần 13, rủi ro #5).

---

## Bộ 14 test tấn công

Chạy mỗi push ([`sandbox-attack.yml`](.github/workflows/sandbox-attack.yml)). **Fail 1 ca =
fail build.** Mỗi ca là một file trong
[`oj-worker/src/test/resources/attacks/`](oj-worker/src/test/resources/attacks/) — thêm ca thứ
15 là thêm một file, không phải sửa một class.

```
 1 fork bomb            8 ptrace tiến trình khác
 2 while(1)             9 symlink thoát /box (+ bẫy đặt cho bước copy-out)
 3 malloc 10GB         10 ★ đọc testdata của chính bài đang chấm
 4 đọc /etc/passwd     11 đọc /proc/self/environ tìm secret
 5 mở socket ra ngoài  12 in 10GB ra stdout
 6 ghi ngoài /box      13 tạo 10.000 file trong /box
 7 exec /bin/sh        14 compiler bomb (template explosion)
```

⛔ Mọi PR chạm `oj-worker/src/main/java/dev/oj/worker/sandbox/` chạy lại **toàn bộ** 14 ca, kể
cả PR "chỉ là refactor" — vì một cờ `isolate` bị đổi trong lúc dọn dẹp trông y hệt một refactor.

Bốn quyết định mà số đo — chứ không phải trực giác — đã chốt:
[`docs/adr/010`](docs/adr/010-input-qua-fd-output-qua-ong.md).
