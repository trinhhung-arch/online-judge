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
cp .env.example .env                      # điền OJ_INTERNAL_SHARED_SECRET (>= 32 ký tự)
./mvnw verify                             # phải xanh trước khi làm bất cứ gì khác
./mvnw -pl oj-api spring-boot:run
```

Worker cần `isolate` trên máy Linux. Cài một lần:

```bash
sudo ./scripts/build-isolate.sh           # build TỪ NGUỒN, không copy binary giữa hai máy
sudo ./scripts/mount-box-tmpfs.sh         # tuỳ chọn, chỉ trên máy nhiều RAM
./mvnw -pl oj-worker spring-boot:run
```

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
| M3 | realtime, đa ngôn ngữ, subtask, feedback level | chưa |
| M4 | auth, quyền, upload đề, MinIO | chưa |
| M5 | contest, bảng xếp hạng | chưa |
| M6 | RabbitMQ, giám sát, deploy | chưa |

```
./mvnw verify   →   216 test xanh
                    oj-api     108 unit + 25 IT (Postgres 16 thật, Testcontainers)
                    oj-worker   57 unit + 26 IT (isolate thật: 14 tấn công + 9 đường chấm + 3 benchmark)
```

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
