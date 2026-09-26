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
| [`docs/bao-mat-plan.md`](docs/bao-mat-plan.md) | kiểm thử bảo mật: lưới đã có, bảy lỗ, checklist OWASP |
| [`docs/giao-dien-plan.md`](docs/giao-dien-plan.md) | giao diện — trang nào, gọi API nào |
| [`docs/ai-review-plan.md`](docs/ai-review-plan.md) | AI review (tuần 14–15, **chưa hiện thực**) |
| [`docs/adr/`](docs/adr/) | quyết định kiến trúc và lý do |

---

## Chạy thử

Trên **máy dev** (không phải host prod — xem [Chạy trên host](#chạy-trên-host-prod)):

```bash
cp .env.example .env                      # điền mật khẩu hạ tầng — trên máy dev giá trị gì cũng được
docker compose up -d                      # postgres · redis · rabbitmq · minio — compose tự đọc .env
./mvnw verify                             # phải xanh trước khi làm bất cứ gì khác (Testcontainers, không cần .env)
( set -a && . ./.env && set +a && ./scripts/chay-dev.sh api )    # API ở :8080, profile dev
```

Ba điều cú pháp trên che đi:

* **`.env` dùng chung cho compose VÀ API**, nhưng **Spring không tự đọc tệp `.env`** — compose
  thì có. Nên API phải chạy trong một shell đã nạp nó; cặp ngoặc `( … )` giữ các biến ấy chỉ sống
  trong lệnh đó. Thiếu bước nạp thì API lùi về mặc định trong `application.yml` (`ojpass`, Redis
  không mật khẩu…) và không nối được vào các container vừa dựng bằng mật khẩu trong `.env`.
* **API bắt buộc BA khoá**, mỗi khoá ≥ 32 ký tự và KHÔNG có mặc định — thiếu là không khởi động,
  cố ý: `OJ_JWT_SECRET` (ký token) · `OJ_INTERNAL_SHARED_SECRET` (worker gọi `/internal`) ·
  `OJ_TOTP_KEY` (mã hoá bí mật 2FA). `chay-dev.sh` tự sinh cả ba **một lần** vào
  `scripts/.secrets-dev` (gitignore) rồi nạp đè lên `.env`, nên máy dev không phải điền chúng.
* **`chay-dev.sh` truyền sẵn `-Dspring-boot.run.profiles=dev`.** Gọi `./mvnw … spring-boot:run`
  tay thì phải tự thêm cờ ấy — xem khung dưới.

> **★ Profile `dev` là bắt buộc trên máy dev, không phải tuỳ chọn.**
>
> `db/dev-seed/` không nằm trong `spring.flyway.locations` mặc định — chỉ `application-dev.yml`
> thêm nó vào. Thiếu profile thì:
>
> * **DB trống** → không có tài khoản nào và không có đề `A-PLUS-B`, nên không đăng nhập được
>   và không nộp được bài. Chính header của `R__seed_du_lieu_dev.sql` giải thích vì sao.
> * **DB đã từng chạy profile `dev`** → Flyway `validate` DỪNG ứng dụng với
>   `Detected applied migration not resolved locally: seed du lieu dev`, vì lịch sử migration
>   có dòng ấy mà `locations` mặc định không tìm ra file. Triệu chứng là API không khởi động
>   được, và thông báo không nhắc gì tới chữ "profile".
>
> Trên host thật thì ngược lại: **không** bật profile `dev`, và lúc đó DB cũng chưa bao giờ
> có dòng lịch sử kia. Chạy nhầm nó trên host là tạo ba tài khoản có mật khẩu viết sẵn trong
> mã nguồn công khai.
>
> ⛔ **Vì thế đừng bao giờ chạy lệnh `chay-dev.sh` ở trên TRÊN HOST PROD.** Ở đó `.env` trỏ vào
> `ojdb_prod`: nạp nó rồi bật profile `dev` là Flyway ghi đè user id 1–3 của prod bằng ba tài
> khoản kia — và việc ghi xảy ra TRƯỚC khi Tomcat kịp báo cổng 8080 đang bận. Chưa có chốt nào
> trong mã chặn việc này.

Worker cần `isolate`, tức là **Linux**. Trên máy dev Linux, cài một lần rồi chạy:

```bash
sudo ./scripts/build-isolate.sh           # build TỪ NGUỒN, không copy binary giữa hai máy
sudo ./scripts/build-pch.sh "-std=gnu++20 -O2"   # tuỳ chọn: biên dịch C++ nhanh gấp ~3,7 lần
sudo ./scripts/mount-box-tmpfs.sh         # tuỳ chọn, chỉ trên máy nhiều RAM
( set -a && . ./.env && set +a && ./scripts/chay-dev.sh worker )  # CÙNG OJ_INTERNAL_SHARED_SECRET với API
```

Trên **macOS** không có cgroup v2 hay namespace Linux, nên worker chạy trong container — đó cũng
là cách máy chấm prod chạy, xem [Chạy trên host](#chạy-trên-host-prod).

### Giao diện

Khi API đã lên (`chay-dev.sh api`), mở **http://localhost:8080** — giao diện là trang tĩnh nằm trong
`oj-api/src/main/resources/static/`, không có build step và không có Node trong CI. Mười hai trang:

| Trang | Nội dung | Đợt |
|---|---|---|
| `/` | danh sách đề, lọc theo tag và theo "đã giải", phân trang cursor | 4.12 |
| `/problem.html?code=…` | đề bài (Markdown render server-side, KaTeX vẽ ở trình duyệt) + ô soạn mã (CodeMirror 6 — hiện lùi về `<textarea>`, xem dưới) + nộp bài | 4.12 |
| `/submission.html?id=…` | chi tiết bài nộp, cập nhật realtime qua SSE, **fallback polling 3 giây** | 4.12 |
| `/login.html` | đăng nhập và đăng ký | 4.12 |
| `/bai-nop.html` | lịch sử bài nộp của mình, lọc đề/verdict/ngôn ngữ | G1 |
| `/ho-so.html` | hồ sơ · đổi mật khẩu · **xác minh email** · xác thực hai lớp | G2 · V11 · V13 |
| `/trang-thai.html` | trạng thái công khai — hàng đợi, máy chấm, thời gian chờ ước tính | G3 |
| `/contests.html` | danh sách kỳ thi | G4 |
| `/contest.html?slug=…` | chi tiết kỳ thi · đăng ký · bảng xếp hạng (SSE) | G5, G6 |
| `/ra-de.html` | soạn/sửa/xuất bản đề · nạp testdata + tiến độ job | G9, G10 |
| `/quan-tri.html` | bảng điều khiển vận hành · rejudge · người dùng · bảo trì | G11 |
| `/nhat-ky.html` | `audit_log` (chỉ ADMIN) | G12 |

> Bảng này từng ghi **"Bốn trang"** và đứng yên suốt M5–M6 trong khi tám trang nữa được
> thêm vào. Một bảng liệt kê thì hoặc đầy đủ, hoặc nói rõ nó chỉ là ví dụ — đứng giữa hai
> điều đó là cách người đọc kết luận rằng `ra-de.html` chưa được viết.

Nháp mã nguồn nằm trong `localStorage`, khoá theo *(đề, ngôn ngữ)* — đổi ngôn ngữ không xoá
mất bản đang viết dở. Nháp **cố ý không gửi lên server**: đó là lời giải chưa nộp, và một
bảng chứa nó là một bảng ADMIN đọc được giữa kỳ thi.

Trình soạn mã **hiện luôn hạ xuống `<textarea>` thường** — mất tô màu cú pháp, **giữ nguyên
khả năng nộp bài**. CodeMirror được tự phục vụ từ `static/vendor/` (không còn CDN), nhưng cây
import của nó kéo bốn bản `@codemirror/state` và chưa từng dựng được trình soạn, kể cả hồi còn
nạp từ CDN; lỗi nay được ghi ra console thay vì bị nuốt. Sửa tận gốc cần một bundle MỘT bản
state — xem [rà soát 2026-09-24](docs/ra-soat-bao-mat-2026-09-24.md), Phần 7.

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

## Chạy trên host (prod)

Host prod là **một máy Mac** (M1 Max, OrbStack) sau **Cloudflare Tunnel** — máy không mở cổng
nào ra internet; mọi dịch vụ nghe `127.0.0.1`. Gập máy thì site đóng, và đó là chủ ý.

| Thành phần | Chạy dưới dạng | Ai bật và giữ sống |
|---|---|---|
| **oj-api** | `java -jar ~/oj-release/oj-api-hien-tai.jar` (symlink tới bản đang chạy) | launchd `dev.oj.api` — [`scripts/khoi-dong-api.sh`](scripts/khoi-dong-api.sh) |
| **oj-worker** | container `oj-worker`, ảnh `oj-worker:arm64` | Docker, `restart: unless-stopped` — [`scripts/trien-khai-mac.sh`](scripts/trien-khai-mac.sh) |
| Postgres · Redis · RabbitMQ · MinIO | `docker compose` | Docker, `restart: unless-stopped` |
| cloudflared | tiến trình | launchd `dev.oj.cloudflared` — [`infra/launchd/`](infra/launchd/) |
| Sao lưu | `pg_dump` 15 phút · base backup 03:15 · WAL | launchd `dev.oj.sao-luu`, `dev.oj.sao-luu-goc` |

**Khởi động lại máy:** đăng nhập là đủ — mọi thứ ở bảng trên tự lên; API thử lại mỗi 30 giây
cho tới khi Postgres sẵn sàng. Kiểm: `curl -s http://127.0.0.1:8080/api/v1/status` có
`"mayChamSong":1`. (LaunchAgent chỉ chạy **sau khi đăng nhập**.)

**Deploy API** — từ gốc repo, trên `main` đã xanh CI:

```bash
./mvnw -B -q -DskipTests package -pl oj-api -am
cp oj-api/target/oj-api-0.0.1-SNAPSHOT.jar ~/oj-release/oj-api-$(git rev-parse --short HEAD)-$(date +%Y%m%d).jar
./scripts/khoi-dong-api.sh doi-jar ~/oj-release/oj-api-<sha>-<ngày>.jar
```

`doi-jar` từ chối nếu có kỳ thi đang chạy hoặc mở trong 30 phút · chép `.env` sang
`~/oj-release/api.env` · sao lưu DB · đổi symlink · khởi động lại (downtime ~5 giây) · đợi dòng
`Started` mới và `/status` 200 · **không lên thì tự lùi về jar cũ**. Migration Flyway chạy lúc
bản mới khởi động — ngay sau bản sao lưu ấy.

* **Lùi bản API:** `./scripts/khoi-dong-api.sh doi-jar ~/oj-release/<jar cũ>` — jar cũ không bị xoá.
* **Sửa `.env`:** chạy lại `doi-jar` với jar đang dùng (`readlink ~/oj-release/oj-api-hien-tai.jar`).
  API dưới launchd đọc **bản chép** `api.env`, vì macOS (TCC) chặn launchd đọc `~/Desktop`.
  Đổi `OJ_INTERNAL_SHARED_SECRET` hay mật khẩu RabbitMQ thì deploy lại cả worker.
* ⛔ **Đừng `kill` API hay `nohup java -jar …`** — launchd dựng lại bản cũ và hai bản giành
  cổng 8080. Đừng chạy jar trong `target/` — mỗi lần build ghi đè nó.

**Deploy worker** — chỉ khi đổi `oj-worker/`, `oj-contract/` hoặc `infra/isolate/`:

```bash
docker tag oj-worker:arm64 oj-worker:truoc-$(date +%Y%m%d)        # đường lùi
( set -a && . ./.env && set +a && OJ_API_BASE_URL=http://host.docker.internal:8080 \
    OJ_RABBIT_HOST=host.docker.internal ./scripts/trien-khai-mac.sh )
./scripts/kiem-sandbox.sh                                          # 14 ca tấn công — bắt buộc
```

Hai biến `host.docker.internal` bắt buộc: `.env` ghi `localhost`, mà trong container `localhost`
là chính container. Script **xoá container cũ, kể cả bài đang chấm dở** (reaper nhặt lại sau
120 giây) — đừng deploy worker trong giờ thi. Đổi `oj-contract` thì API và worker phải lên cùng lúc.

**Kiểm sau deploy:** `./scripts/kiem-tunnel.sh <tên-miền>` (36 phép kiểm biên công khai) · log API
`~/oj-release/api.log` · log worker `docker logs -f oj-worker` · log tunnel
`~/Library/Logs/cloudflared-oj.log`.

**Khôi phục DB** — [`scripts/khoi-phuc-db.sh`](scripts/khoi-phuc-db.sh): mặc định là **diễn tập**
(restore vào DB tạm, đối chiếu số dòng, xoá). Trên host luôn đặt `OJ_DB_NAME=ojdb_prod` — mặc
định của script là `ojdb` (DB dev), kể cả với `--that`. Quy trình PITR nằm ở đầu chính file ấy.

## Trạng thái

| Mốc | Nội dung | Trạng thái |
|---|---|---|
| M0 | hạ tầng, schema, hợp đồng | xong |
| M1 | vòng nộp bài → verdict, **không thực thi mã người dùng** | xong |
| **M2** | **sandbox `isolate` + 14 test tấn công** | **xong** (xem dưới) |
| M3 | realtime (SSE + Redis), subtask, feedback level | xong |
| **M4** | auth, quyền, upload đề, MinIO, giao diện | **xong 4.1–4.12** (xem dưới) |
| **M5** | kỳ thi, bảng xếp hạng | **xong 5.1–5.11** (xem dưới) |
| **M6** | RabbitMQ, giám sát, vận hành | **xong 6.1–6.15** (xem dưới) |
| **v1.1** | xác minh email (FR-AUTH-09) — **mức mềm**, không chặn gì | **xong** — V13, [ADR 016](docs/adr/016-xac-minh-email-muc-mem.md) |
| bảo mật · vận hành | rà soát 22–25/09: `audit_log` chỉ ghi thêm (V14) · trần mã 2FA theo tài khoản + IPv6 theo /64 (V15) · bỏ CDN khỏi đường chạy script · vá 12 CVE · API do launchd giữ sống | **xong** — [rà soát 2026-09-24](docs/ra-soat-bao-mat-2026-09-24.md), phần việc còn mở ở Phần 8 |

```
./mvnw verify   →   843 test xanh trên Linux · 817 trên macOS   (đo 2026-09-25)
                    CI chạy 839: bỏ 4 ca MinioTestdataStoreIT (-DexcludedGroups=minio-that) vì
                    MinIO thôi phát ảnh công khai từ 2026-09-26 — xem javadoc lớp ấy. Máy không
                    có sẵn ảnh MinIO thì cũng thêm cờ ấy khi chạy verify.

                    oj-api      455 unit + 283 IT   Postgres 16 + Redis 7 + MinIO thật, Testcontainers
                    oj-worker    79 unit +  26 IT   isolate thật: 14 tấn công + 9 đường chấm
                                                    + 3 benchmark — CHỈ chạy trên Linux
                    oj-contract   0                 (bản trước ghi "8 unit" — hiện không còn test nào)

                    ⚠️ macOS không chạy được 26 IT của oj-worker, và nó KHÔNG báo đỏ:
                    Assumptions.abort() huỷ cả class rồi vẫn in BUILD SUCCESS. Dùng
                    scripts/kiem-sandbox.sh — nó ĐẾM số ca.
```

### M4 — toàn bộ 12 bước

| Bước | Nội dung | Bằng chứng |
|---|---|---|
| 4.1 | migration **V5** — `refresh_tokens` · `login_attempts` · `login_lockouts` · `audit_log` phân mảnh theo tháng | chạy trên DB rỗng (Testcontainers) **và** DB dev đã có dữ liệu: 387ms |
| 4.2–4.4 | `identity` đầy đủ: domain thuần · 8 use-case · BCrypt cost 12 · refresh token lưu **SHA-256** | `IdentityDomainTest` 14 · `IdentityUseCasesTest` 22 |
| 4.5 | JWT HS256 **không thêm dependency** · `JwtAuthFilter` · `JwtCurrentUserProvider` thay `FixedDevUserProvider` | `JwtTest` 15 ca, gồm 4 lớp CVE của thư viện JWT · [ADR 012](docs/adr/012-tu-viet-jwt-hs256-thay-vi-them-thu-vien.md) |
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

### M5 — toàn bộ 11 bước

| Bước | Nội dung | Bằng chứng |
|---|---|---|
| 5.1 | migration **V7** — `contests` · `contest_problems` · bốn bảng xếp hạng · `standings_drift_checks` | chạy trên DB rỗng và DB dev đã có dữ liệu |
| 5.2 | `ContestFormat` + `IcpcFormat` + `IoiFormat` — thêm thể thức = **1 file + 1 dòng + 1 migration**, cả ba đều hỏng ồn ào nếu quên | `ContestFormatTest` 14 |
| 5.3 | `ContestWindowQuery` ở `platform` — **một câu, bốn nơi dùng**: khoá đề · cấm sửa đề · gán `contest_id` · (AI review, tuần 14–15) | `ContestAccessIT` |
| 5.4 | FR-CON-03 — đề chỉ mở trong khung giờ, kiểm ở use-case, trả **404 chứ không 403** | `ContestAccessIT` 7 |
| 5.5 | `RegisterForContestUseCase` — đóng đăng ký đúng lúc chuông reo | `ContestAccessIT` |
| 5.6 | `StandingsUpdater` — lô mỗi 2 giây, idempotent theo `last_applied_submission_id`, **cả lô một transaction** | `ContestStandingsIT` 6 |
| 5.7 | `RedisStandingsCache` (top N) + `JdbcStandingsReader` (đường dự phòng) | `ContestStandingsIT` |
| 5.8 | `FreezeStandingsScheduler` — chụp một lần, ép ở hai lớp | `ContestStandingsIT` |
| 5.9 | `RebuildStandingsJob` — job nền có tiến độ, **không có logic riêng** | `StandingsJobsIT` 7 |
| 5.10 | `StandingsDriftCheckJob` — chỉ đo, không tự sửa | `StandingsJobsIT` |
| 5.11 | `RevealStandingsUseCase` — đề và AI review mở tự động, bảng xếp hạng **chờ người bấm** | `ContestStandingsIT` |

Rate limit và hai truy vấn lịch thi là ba chặng mới trên đường nộp bài kể từ M3. Đo lại sau
M5: `p50=7ms · p95=11ms` trên 100 mẫu — ngân sách P2 là 300ms.

> **Đóng băng không tự hết khi hết giờ.** Nó kéo dài tới khi có người công bố. Đó là cả điểm
> của nghi thức trao giải kiểu ICPC: bảng vẫn kín sau tiếng chuông, và được mở ra trước mặt
> mọi người. Tự mở lúc `ends_at` là xoá mất khoảnh khắc ấy, và không lấy lại được.

**FR-CON-10 (virtual participation) chưa làm** — `frplan.md` xếp nó là ứng viên bị cắt đầu tiên
và PHẦN 7 không liệt kê nó trong 5.1–5.11.

### Ba lỗi nữa chỉ hiện ra khi chạy thật

| Lỗi | Vì sao test không thấy trước | Đã chốt lại bằng |
|---|---|---|
| `StandingsUpdater` ghi dòng theo đề **trước** dòng tổng | khoá ngoại `contest_problem_standings → contest_standings` chỉ vỡ ở lần nộp ĐẦU TIÊN của một người, và thông báo lỗi không nhắc tới bảng đang thiếu dòng | `ContestStandingsIT` — bốn ca đỏ ngay lần chạy đầu |
| `XepHangKey` — phép gói ba tầng vào một `double` | nó *chạy đúng* và có 5 ca xanh. Vấn đề là nó giải một bài toán thiết kế này không có: cache chỉ chứa top N, và top N đã được Postgres sắp đúng | xoá hẳn; điểm ZSET giờ là **vị trí**, không trần giá trị, không nhánh đặc biệt |
| Cache bảng xếp hạng đứng im sau khi sửa tay bằng SQL | chỉ đường ghi của ứng dụng mới xoá cache — đúng thiết kế, nhưng người sửa tay lúc 2 giờ sáng cần biết trước | ghi vào javadoc của TTL, kèm `DEL oj:standings:*` |

> **Ba lập trường phân quyền, và không có lập trường thứ tư.** Mọi class `*UseCase` phải mang
> `@RequiresRole`, `@PublicAccess` hoặc `@InternalAccess`; LUẬT 8 fail CI nếu thiếu. Nhờ đó
> `grep -rn "@PublicAccess" oj-api/src/main` liệt kê **đủ mọi lối vào không cần đăng nhập** của
> cả hệ thống — trang đầu tiên phải đọc trong buổi tấn công chéo tuần 9.

---

### M6 — toàn bộ 15 bước

| Bước | Nội dung | Bằng chứng |
|---|---|---|
| 6.1 | migration **V8** (phân quyền `oj_app`) + **V9** (một job đang sống mỗi *thực thể*) | `MigrationTrenDuLieuCoSanIT` — chạy tới V8, chèn dữ liệu, rồi migrate tiếp. Kiểm tay dưới role `oj_app` thật: 6 lệnh bị từ chối, 3 lệnh phải chạy được thì chạy được |
| 6.2 | khung job nền — **đã có từ M4**; M6 thêm luồng riêng + trạng thái `PAUSED` | `JobsIT` · `RejudgeIT` |
| 6.3 | ★ `RejudgeJob`: hai hàng đợi · trần 30% · phanh khi live chờ >5s · cấm khi có kỳ thi | `RejudgeJobTest` 9 (domain thuần) · `RejudgeIT` 6 · `AdminJudgingUseCasesTest` 8 |
| 6.4 | ★ Postgres queue → RabbitMQ: quorum queue · `prefetch=1` · ack tay · DLQ sau 3 lần | `RabbitJudgeJobPublisherTest` 4 · `JudgeDoorbellTest` 3 · kiểm tay: 3 quorum queue, 1 consumer mỗi hàng |
| 6.5 | `audit_log` đọc được (ADMIN, phân trang) + job tạo partition hàng tháng | `VanHanhIT` — gồm ca phân trang khi trùng mốc thời gian |
| 6.6 | `AccountAdminController`: đổi vai trò, vô hiệu hoá — **không xoá cứng** | `AccountManagementUseCasesTest` 6 |
| 6.7 | `/actuator/health` thật: cả **hai** pool Postgres · Redis · RabbitMQ · máy chấm sống | `SuyGiamIT` · kiểm tay ở cổng 8081 |
| 6.8 | tắt worker êm bằng SIGTERM | `WorkerContextSmokeTest` · kiểm tay: SIGTERM → "Mọi slot đã chấm xong bài của mình và dừng" |
| 6.9 | degraded mode 5 kịch bản | `SuyGiamIT` 5 · `CodingRulesTest` LUẬT 9 (MinIO) |
| 6.10 | Micrometer P1–P8 + `GET /api/v1/admin/ops` | `VanHanhHttpIT` · kiểm tay: 9 ô số liệu |
| 6.11 | trang trạng thái công khai — truy vấn 12, đếm trên `judge_queue` | `VanHanhHttpIT` — gồm ca "không lộ mã đề hay submissionId nào" |
| 6.12 | công tắc `submissions.accepting` | `VanHanhHttpIT` — gồm ca "bài đang chấm vẫn chấm xong" |
| 6.13 | `HideSubmission` + FR-SUB-12 hoàn chỉnh (gõ cửa sau khi IE quay lại hàng đợi) | `AdminJudgingUseCasesTest` · `RecordJudgeResultUseCaseTest` +2 |
| 6.14 | FR-PROB-10/11/12 | kiểm tay: nạp testdata phiên bản 2 → job `REJUDGE` **tự sinh** với `total_items: 6` |
| 6.15 | đếm query chống N+1, JDK thuần | `DemQuery` (80 dòng, `java.lang.reflect.Proxy`) · `DemQueryIT` — 30 dòng dữ liệu vẫn ≤2 truy vấn |

**Bước 6.4 chạm đúng hai file phía API** (`RabbitJudgeJobPublisher`, `NoopJudgeJobPublisher`)
— con số mà `build-order.md` đặt làm thước đo cho việc M1 có làm đúng hay không. Không một
use-case nào, không một câu SQL nào phải sửa.

**Thông điệp là một *tiếng chuông*, không phải một gói việc.** Thân message chỉ có
`submissionId`, và worker không dùng con số đó để chọn bài — nó vẫn gọi `claim`. Nhờ vậy
`oj-contract` không đổi một dòng, mã nguồn người dùng không vào ổ đĩa của broker, và message
không bao giờ cũ khi reaper tăng `attempt`. Đổi lại, mệnh đề *"ack sau khi kết quả đã vào DB"*
của Bước 6.4 không áp dụng nguyên văn: ack xảy ra sau khi rung chuông. Bảo đảm mà nó nhắm tới
vẫn còn và **mạnh hơn** — nó do `judge_queue` + lease 120s + reaper cung cấp, không do broker.

### v1.1 — xác minh email (FR-AUTH-09)

| Nội dung | Bằng chứng |
|---|---|
| migration **V13** — `email_verifications` · cột `users.email_verified_at` · `ck_users_anonymized` mở rộng | `MigrationTrenDuLieuCoSanIT` — chạy tới V12, chèn một tài khoản **đã ẩn danh hoá**, rồi migrate tiếp |
| mã 6 chữ số · hạn 30 phút · 5 lần thử · mã mới huỷ mã cũ · lưu **SHA-256** | `MaXacMinhEmailTest` 7 · `XacMinhEmailUseCaseTest` 16 — mỗi hàng rào một ca riêng |
| `POST /api/v1/me/xac-minh-email` + `/xac-nhan`, **cần đăng nhập** · SMTP có trần 10s, thiếu thì không boot | `XacMinhEmailHttpIT` 5 |

**Mức mềm: nhãn `email_verified_at` không chặn gì cả** — chưa xác minh thì vẫn đăng nhập, vẫn
nộp bài, vẫn dự thi. Đó là điều kiện để nhận thêm SMTP vào hệ thống: nhà cung cấp thư chết thì
hôm ấy không ai xác minh được, và **không ai mất quyền vào hệ thống**. Chặn đăng nhập thì một
sự cố của họ trở thành sự cố truy cập của ta, đúng vào ngày contest.

**Mặc định TẮT** (`OJ_EMAIL_VERIFICATION_ENABLED=false`) vì máy dev và CI không có máy chủ thư.
Bật thì cần `OJ_MAIL_HOST` + `OJ_MAIL_FROM`, và địa chỉ gửi phải thuộc tên miền đã cấu hình
SPF/DKIM — "mã rơi vào spam" là trải nghiệm tệ hơn không có xác minh.

**Nó KHÔNG thay Turnstile.** Hai hàng rào, hai mục tiêu: Turnstile chặn bot tạo tài khoản hàng
loạt, xác minh email cho một kênh liên lạc đã xác minh. Tắt cái sau không hạ mức chống bot đi
chút nào; tắt cái trước thì có.

### Kill RabbitMQ — bài test quan trọng nhất sau Bước 6.4

```
docker compose stop rabbitmq
  nộp #1..#5  →  202 trong 0.019–0.031s      hàng đợi: 4 → 9, không mất bài nào
  log:           đúng MỘT dòng WARN, không phải năm
docker compose start rabbitmq
  log:           "RabbitMQ đã trở lại — gõ cửa hoạt động lại."
```

Một dòng WARN thay vì năm là nhờ cầu dao trong `RabbitJudgeJobPublisher`. Nuốt ngoại lệ giữ
cho bài nộp **đúng**; nó không giữ cho bài nộp **nhanh**. Mỗi lần publish vẫn phải mở một kết
nối TCP rồi chờ nó hỏng, và timeout mặc định của Spring AMQP là **60 giây** — với nó, P2
không còn là 300ms mà là một sự cố toàn hệ thống. Hai lớp chữa:
`spring.rabbitmq.connection-timeout: 200ms` là trần cho một lần thử, cầu dao là thứ khiến chỉ
có một lần thử mỗi 10 giây.

### Năm lỗi chỉ hiện ra ở M6 — và hai trong số đó đã sống từ M1

| Lỗi | Sống từ | Vì sao không test nào thấy | Đã chốt lại bằng |
|---|---|---|---|
| ★ **Tiến trình `oj-worker` chưa từng khởi động được** — không bean `RestClient.Builder` | M1 | mọi test của `oj-worker` dựng đối tượng bằng `new`; **không test nào dựng Spring context** | `JudgeApiClientConfig` + `WorkerContextSmokeTest` |
| ★ `LocalDirectoryTestdataSource` không bao giờ được đăng ký — `@ConditionalOnMissingBean` trên một `@Component` | M2 | như trên. Annotation ấy **chỉ đáng tin trong auto-configuration** | gỡ annotation; xung đột hai hiện thực giờ hỏng ồn ào, đúng ý định ban đầu |
| `JobRunner` chạy job **đồng bộ trên luồng lập lịch chung** với reaper | M4 | pool `spring.task.scheduling` mặc định có **một** luồng; một job nạp 200MB chặn reaper suốt thời gian đó → bài kẹt `JUDGING` quá 120s mà không ai thu hồi (R1) | executor riêng, cùng cách `SseHeartbeat` đã làm ở M3 |
| Con trỏ phân trang `audit_log` cắt xuống **mili** giây | M6 | `timestamptz` có độ chính xác **micro** giây → trang hai thiếu dòng, và chỉ thiếu khi các bản ghi rơi vào cùng một mili giây | ca `cung_mili_khac_micro_khong_bo_sot`, đã kiểm chứng bằng cách trả lại lỗi cũ |
| Cache `system_settings` nhớ **mặc định của người gọi đầu tiên** | M6 | khoá vắng mặt + hai người gọi có hai mặc định khác nhau → mượn mặc định của nhau. `ai_review.enabled` mặc định `false`, `submissions.accepting` mặc định `true` | cache giữ ba trạng thái: `true` · `false` · *không có* |

Hai lỗi đầu cùng một nguyên nhân, và nó đáng nói thẳng: **một bộ test toàn unit test không
trả lời được câu hỏi "tiến trình này có chạy được không"** — với một hệ thống hai tiến trình
thì đó là câu hỏi quan trọng thứ hai, ngay sau tính đúng đắn. `oj-worker` có gần một trăm
test và không cái nào hỏi câu ấy.

Ngoài bảng: sai tên phần multipart trả **500 "có lỗi phía hệ thống"** thay vì 400, và kho
MinIO chết bị phân loại **400 thay vì 503** — cả hai đều là phân loại sai làm một sự cố hạ
tầng biến mất khỏi mọi biểu đồ theo dõi.

### Ba con số của M6

| | |
|---|---|
| trần rejudge | **2 trên 6 slot** = 30% năng lực. `HopDongVanHanhTest` đọc cả `oj-api` lẫn `oj-worker/application.yml` và đỏ khi hai con số lệch nhau |
| `/actuator/**` | cổng **8081**, không phải 8080 — Cloudflare Tunnel chỉ publish 8080, nên nó không có lối vào từ internet. Cùng cơ chế đã dùng cho `/internal/judge/*` |
| health | `/actuator/health` cho **người** (gộp mọi thành phần) · `/actuator/health/sanSang` cho **máy** (chỉ Postgres). Bảng degraded mode có năm thành phần và đúng một trong năm là chí mạng; trả 503 cho bốn cái còn lại là bảo bộ giám sát restart một API đang chạy đúng |

## Baseline sandbox — Bước 2.10

⚠️ **Số dưới đây KHÔNG phải số của máy chấm chuẩn.** Chúng đo trên máy dev WSL2 x86, và
`nfrplan.md` 9.1 nói rõ: *một con số thời gian không kèm tên máy là một con số vô nghĩa*.
Chúng dùng để **kiểm đúng/sai**, không dùng để đặt giới hạn thời gian cho đề.

Máy chấm chuẩn là `mac-m1max-host` (arm64, 6 slot, `host_factor = 1.000` theo định nghĩa).
**Đã deploy và hiệu chuẩn ngày 2026-09-05** — container `oj-worker:arm64`, 7/7 ca kiểm bên
trong container xanh, `OJ_HOST_REFERENCE_CPU_MS=461`.

| Phép đo | Máy dev (WSL2, i7-9850H, 12 luồng, 7GB) | **Máy chấm chuẩn** (M1 Max, arm64) |
|---|---|---|
| `isolate` | 2.6, cgroup v2, subuid | 2.6, cgroup v2, subuid ✔ |
| **Tải chuẩn `HostBenchmark`** | **630 ms CPU** (trung vị 5 lần) | **461 ms CPU** (2 mẫu lạnh: 461 · 462, lệch 0,2%) |
| Biên dịch A+B (`bits/stdc++.h`, `-O2 -static`) | 2,83 s CPU · đỉnh 229 MB | chưa đo riêng |
| Chạy A+B | ~3 ms CPU · 1,6 MB | 1–2 ms (đọc từ `judge_runs.time_ms`) |
| `--cleanup` + `--init` một box | ~5 ms | chưa đo riêng |
| 14 test tấn công, cả bộ | 12,7 s | **5,6 s** (`kiem-sandbox.sh`, 14/14) |
| 9 ca đường chấm thật | 14,6 s | chưa đo riêng |
| Năng lực chấm (6 slot, source duy nhất) | chưa đo | **~350 bài/phút = 5,8 bài/s** |

> **Vì sao mốc là 461 chứ không phải 462.** `host_factor = đo / mốc`, nên mốc thấp hơn cho hệ
> số ≥ 1, tức giới hạn thời gian rộng hơn một chút — lệch về phía **không TLE oan**. Hai mẫu
> chỉ cách nhau 0,2%, dưới xa ngưỡng cảnh báo trôi 8%.
>
> **Ô "chưa đo riêng" là thật, không phải quên.** Bốn con số ấy đo bằng tay trên máy dev; trên
> máy chấm chúng chưa được tách ra khỏi phép đo tổng. Đừng điền số ước lượng vào đó.

**Cách đo lại trên một máy bất kỳ:**

```bash
./mvnw -pl oj-contract,oj-worker -am verify        # 14/14 phải xanh trước đã
( set -a && . ./.env && set +a && ./scripts/chay-dev.sh worker )   # log dòng "Đo máy ... ms CPU"
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
