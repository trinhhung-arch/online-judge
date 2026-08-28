# Thiết kế PostgreSQL cho Online Judge v1.0

> Tài liệu này trả lời câu hỏi *"schema trông thế nào và vì sao"*.
> Nó phục tùng ba tài liệu đã có: `CLAUDE.md` (12 bất biến), `nfrplan.md` (SLO), `frplan.md` (FR).
> Khi mâu thuẫn, ba tài liệu kia thắng.
>
> Toàn bộ DDL ở `migration/`, các truy vấn nóng ở `queries/duong_nong.sql`,
> bộ kiểm chứng ở `queries/smoke_test.sql`.
> **Mọi con số đo trong tài liệu này đều chạy thật trên PostgreSQL 16.13**, không phải ước lượng.

---

## 0 · Một câu chi phối

Ba thứ hệ thống bán là **công bằng · không mất bài · an toàn**. Ở tầng database, ba thứ đó
biến thành ba câu cụ thể:

| Thứ phải bảo vệ | Cách schema bảo vệ |
|---|---|
| Không rò rỉ testdata (SEC3) | **Postgres không có chỗ nào để lưu nội dung testcase ẩn.** Không phải "đừng lưu" — mà là không có cột nào nhận được nó, và ràng buộc khoá ngoại làm việc lưu nhầm trở nên bất khả thi. |
| Không mất bài (R1) | Bài nộp coi là xong **khi và chỉ khi** hàng của nó bị xoá khỏi `judge_queue` trong cùng transaction với việc ghi verdict. Còn hàng ở đó là còn được chấm lại. |
| Không chấm hai lần (R2) | Khoá lạc quan theo `attempt`, đặt ở `judge_queue` chứ không ở `submissions` — xem mục 3. |

Ba câu này quyết định gần như toàn bộ những gì còn lại.

---

## 1 · Sáu quyết định lớn, và cái giá của mỗi cái

| # | Quyết định | Được gì | Trả giá gì |
|---|---|---|---|
| 1 | **Tách hàng đợi ra bảng riêng `judge_queue`; `submissions.status` KHÔNG có index** | 100% update trên bảng nóng là HOT · bảng nóng chỉ tiêu 2/4 suất index · "dựng lại hàng đợi khi mất RabbitMQ" thành index scan trên bảng vài trăm dòng | Thêm một bảng, và trạng thái sống ở hai chỗ (`submissions.status` là ảnh chụp, `judge_queue` là sự thật) |
| 2 | **Nội dung testcase không nằm trong Postgres**; chỉ metadata + `sha256`, nội dung trên MinIO | SEC3 được ép ở tầng schema, không phụ thuộc code | Trang đề phải đọc MinIO cho test sample → nên có bảng `sample_testcase_contents` riêng, chỉ chứa test công khai |
| 3 | **KHÔNG lưu kết quả từng test**; chỉ verdict + `failed_test_ordinal` + điểm từng subtask | Tiết kiệm ~50M dòng và tránh một kho dữ liệu mà không ai được phép xem | Muốn dựng lại chi tiết từng test thì phải rejudge |
| 4 | **KHÔNG partition `submissions`** (nhưng `audit_log` thì có) | Truy vấn nóng nhất — "lịch sử của user X" — không bị chia nhỏ ra N partition | Bảng lớn dần; điều kiện kích hoạt partition ghi ở mục 7 |
| 5 | **Rejudge tạo `attempt` mới trong `judge_runs`, không ghi đè** | FR-ADM-01 · đối chiếu được khi rejudge cho kết quả khác | Thêm 1 bảng dài bằng `submissions` (đo thật: 127 MB cho 1M dòng) |
| 6 | **Redis là cache, `contest_standings` là sự thật** | Redis chết vẫn ra đúng thứ hạng, chỉ chậm hơn | Phải có job đối soát drift (FR-CON-09), và job đó không được cắt |

---

## 2 · Sơ đồ quan hệ

```
users ──┬──< submissions >──── problems ──< testdata_versions ──< testcases
        │        │  │                            │                   │
        │        │  │                            └──< subtasks        └──1 sample_testcase_contents
        │        │  │                                                     (CHỈ test sample — ép bằng FK)
        │        │  └──1 judge_queue        (hàng đợi bền, vài trăm dòng)
        │        │  └──*  judge_runs        (một hàng / attempt, không bao giờ sửa)
        │        │            └──* judge_run_subtasks
        │        └──1 source_blobs          (khử trùng lặp theo sha256)
        │
        ├──< refresh_tokens · login_attempts
        ├──< contest_registrations >── contests ──< contest_problems >── problems
        │                                   └──< contest_standings ──< contest_problem_standings
        │                                   └──< contest_standings_frozen (ảnh chụp lúc freeze)
        ├──< ai_reviews · ai_quota_usage
        └──< audit_log  (PARTITION BY RANGE theo tháng, append-only)

languages · judge_hosts · host_benchmarks · system_settings · jobs · queue_metrics   (bảng nguội)
```

**Đường một chiều cần nhớ:** `ai_reviews → submissions`, không có chiều ngược.
Không cột nào của `submissions` bị package `ai` ghi vào — đó là AI1 (0ms thêm vào đường chấm)
được diễn đạt ở tầng dữ liệu.

---

## 3 · Vòng đời một bài nộp, nhìn từ database

Đây là phần quan trọng nhất. Toàn bộ SQL nguyên văn ở `queries/duong_nong.sql`.

```
POST /api/v1/submissions                     ┌─ 1 transaction, ngân sách 50ms ─┐
   INSERT source_blobs  ON CONFLICT DO NOTHING
   INSERT submissions   (status=QUEUED, attempt=0)
   INSERT judge_queue   (priority=0, attempt=0)
   COMMIT ────────────────────────────────────┘
   publish RabbitMQ   ← NGOÀI transaction. Hỏng cũng không sao: hàng đã nằm trong
                        judge_queue, reaper sẽ nhặt. Đây là lý do reaper tồn tại.
   return 202 + submissionId

POST /internal/judge/claim
   UPDATE judge_queue ... FOR UPDATE SKIP LOCKED, attempt = attempt + 1
   ▲ attempt tăng ở ĐÂY. Chỉ riêng điều này đã vô hiệu hoá kết quả trả về muộn
     của một worker đã bị reaper thu hồi — không cần thêm cơ chế nào.

POST /internal/judge/result                  ┌─ 1 transaction ─┐
   DELETE judge_queue WHERE submission_id=? AND attempt=?   ← KHOÁ LẠC QUAN
        0 dòng → kết quả trùng hoặc kết quả của attempt đã chết → bỏ qua, im lặng
        1 dòng → tiếp
   INSERT judge_runs (submission_id, attempt, ...)   ← PK chặn trùng lớp thứ hai
   UPDATE submissions SET status='DONE', verdict=... ← HOT update
   COMMIT ───────────────────────────────────┘

Reaper mỗi 15s
   UPDATE judge_queue SET claimed_at=NULL WHERE lease_until < now()
   (KHÔNG tăng attempt ở đây — lần claim kế tiếp mới tăng)
```

**Vì sao khoá lạc quan đặt ở `judge_queue` chứ không phải `submissions`?**
`CLAUDE.md` bất biến #7 viết `WHERE id=? AND attempt=? AND status='JUDGING'`. Ngữ nghĩa
giữ nguyên, nhưng nếu đặt trên `submissions` thì `status` và `attempt` phải được index để
câu lệnh nhanh, và ngay khi index chúng thì mọi UPDATE trên bảng nóng mất khả năng HOT
(số đo ở mục 4). Đặt trên `judge_queue` cho cùng một sự bảo đảm, trên một bảng vài trăm dòng.

**Kiểm chứng đã chạy** (`queries/smoke_test.sql`, 12 ca):

| Ca | Kỳ vọng | Kết quả |
|---|---|---|
| Worker cũ (attempt=1) trả kết quả sau khi reaper thu hồi | 0 dòng, không ghi gì | ✅ |
| Giao trùng lần 2 sau khi đã ghi xong | 0 dòng, verdict cũ nguyên vẹn | ✅ |
| Lưu nội dung cho testcase ẩn | Lỗi khoá ngoại | ✅ |
| Quota AI lần thứ 6 | 0 dòng (từ chối) | ✅ |
| Job REJUDGE thứ hai khi đã có một cái đang chạy | Lỗi unique | ✅ |

---

## 4 · Ngân sách index trên bảng nóng — có số đo

`nfrplan.md` 2.3 đặt trần **3–4 index** trên `submissions`. Thiết kế này dùng **2** (ngoài PK),
và để dành phần còn lại.

| Index | Phục vụ | Kích thước ở 1M dòng |
|---|---|---|
| `submissions_pkey (id)` | mọi thứ | 21 MB |
| `ix_submissions_user_recent (user_id, id DESC) INCLUDE (problem_id, language_id, created_at)` | FR-SUB-07 lịch sử · FR-SUB-08 rate limit dự phòng | 94 MB |
| `ix_submissions_problem_recent (problem_id, id DESC)` | FR-ADM-01 rejudge theo đề · FR-CON-08 dựng lại bảng xếp hạng | 53 MB |

Cố ý **không** có index trên `status`, `verdict`, `contest_id`, `created_at`.

**`status` — số đo thật.** Mô phỏng 20.000 bài nộp trọn vòng đời
(INSERT → UPDATE JUDGING → UPDATE DONE), cùng `fillfactor=85`:

| Cấu hình | Update là HOT | Kích thước index sau đó |
|---|---|---|
| Không index trên `status` (đã chọn) | **40.000 / 40.000 — 100%** | 45 MB |
| Có index trên `status` | **0 / 40.000 — 0%** | 56 MB |

HOT update nghĩa là Postgres không phải ghi lại bất kỳ index nào. Với 3 index trên bảng nóng,
đó là chênh lệch 6 lần ghi index mỗi bài nộp — nhân với mọi bài nộp, mãi mãi.

**`contest_id`** — dựng lại bảng xếp hạng chạy theo từng đề và chặn khoảng `id`
(bài trong contest luôn nằm giữa id đầu và id cuối của khung giờ thi), nên
`ix_submissions_problem_recent` đã đủ. Xem truy vấn 11 trong `duong_nong.sql`.

**`created_at`** — không cần: `id` tăng đơn điệu nên `ORDER BY id DESC` chính là thứ tự thời gian.
Đây cũng là lý do khoá chính là `BIGINT IDENTITY` chứ không phải UUID.

### Kích thước thật ở 1 triệu bài nộp (S3)

```
submissions   1.000.000 dòng   heap 217 MB   index 169 MB   tổng 386 MB
judge_runs    1.000.000 dòng   heap  96 MB   index  30 MB   tổng 127 MB
cả database                                                  524 MB
```

Truy vấn lịch sử bài nộp ở 1M dòng: **0,08 ms, 8 buffer**. Rate-limit dự phòng:
**Index Only Scan, Heap Fetches = 0**. S3 ("1M+ dòng, p95 giữ nguyên") không phải là vấn đề
với thiết kế này — vấn đề chỉ xuất hiện nếu ai đó thêm index thứ 5 hoặc một `COUNT(*)`.

---

## 5 · Ba bất biến được ép ở tầng schema, không phụ thuộc code

Đây là phần đáng giá nhất của một schema tốt: những thứ mà **quên viết `if` cũng không sai được**.

**1. Nội dung testcase ẩn không thể lưu vào DB.**

```sql
-- testcases:                     UNIQUE (id, is_sample)
-- sample_testcase_contents:      is_sample BOOLEAN NOT NULL DEFAULT TRUE CHECK (is_sample),
--                                FOREIGN KEY (testcase_id, is_sample)
--                                    REFERENCES testcases (id, is_sample)
```

Muốn lưu nội dung cho một testcase ẩn, khoá ngoại phải khớp `(id, FALSE)` — nhưng cột
`is_sample` bên bảng nội dung bị `CHECK` ép luôn bằng `TRUE`. Không có đường nào đi qua.
Đã kiểm: chèn thử trả về lỗi khoá ngoại.

**2. Không ai xoá được bài nộp, kể cả ADMIN, kể cả qua SQL.**
`REVOKE DELETE, TRUNCATE ON submissions FROM oj_app` (V9). FR-SUB-09 trở thành quyền hệ thống,
không phải một nút bị ẩn.

**3. `audit_log` và `judge_runs` chỉ ghi thêm.**
Cùng cơ chế: `REVOKE UPDATE, DELETE`. Trigger thì tắt được, quyền thì không.

Thêm ba hàng rào nhỏ nhưng cứu được ngày tệ nhất:

- `ux_jobs_one_active_per_type` — một cú double-click trên trang admin không tạo ra ba job
  rejudge hàng loạt chạy song song.
- `ux_judge_hosts_single_reference` — chỉ tồn tại đúng một "máy chấm chuẩn". Mọi con số thời
  gian quy chiếu về nó (FR-SUB-11).
- `ck_problems_epsilon` — `checker_epsilon` bắt buộc có khi và chỉ khi `checker_type='float'`.

---

## 6 · Quota AI: một câu, nguyên tử

FR-AI-03 (5 review/ngày/user) là chỗ rất dễ viết sai thành `SELECT` → `if` → `UPDATE`,
và hai tab trình duyệt là đủ để lách.

```sql
INSERT INTO ai_quota_usage (user_id, usage_date, used_count)
VALUES (:userId, CURRENT_DATE, 1)
ON CONFLICT (user_id, usage_date) DO UPDATE
   SET used_count = ai_quota_usage.used_count + 1
 WHERE ai_quota_usage.used_count < :dailyLimit
RETURNING used_count;
```

0 dòng trả về = hết quota. Không transaction lồng, không khoá tường minh, không race.
Đã kiểm: lần 1–5 trả về 1..5, lần 6 trả 0 dòng.

Lưu ý vì sao không đếm bằng `COUNT(*)` trên `ai_reviews`: có review **không** trừ quota —
bản dùng lại từ cache theo `sha256(source)` và bản LLM lỗi (FR-AI-08).

---

## 7 · Vì sao `audit_log` partition mà `submissions` thì không

Câu hỏi này chắc chắn bị hỏi lúc bảo vệ, nên trả lời sẵn:

| | `submissions` | `audit_log` |
|---|---|---|
| Kiểu ghi | insert + 2 update | chỉ insert |
| Truy vấn nóng nhất | "lịch sử của user X" — **không có khoảng thời gian** | "ai làm gì trong tháng 3" — **luôn có khoảng thời gian** |
| Partition pruning giúp được gì | Gần như không. Một user nộp bài rải rác 12 tháng thì mọi trang lịch sử phải mở cả 12 partition, dừng khi đủ `LIMIT` | Cắt thẳng còn 1 partition |
| Có xoá dữ liệu cũ không | **Không bao giờ** (FR-SUB-09) → mất luôn lợi ích lớn nhất của partition là `DROP PARTITION` | Có thể lưu trữ lạnh sau 1–2 năm |
| Kết luận | **Không partition ở v1.0** | **Partition theo tháng ngay từ đầu** |

Task 6.2 của plan gốc ("partition `submissions` theo tháng") vì thế được đề nghị **hoãn**, kèm
điều kiện kích hoạt rõ ràng — đây là mục cần người quyết, xem mục 14.

**Điều kiện nên partition `submissions`:** khi `pg_total_relation_size` vượt ~20 GB, **hoặc**
khi autovacuum trên bảng này chạy quá 10 phút một lượt. Ở 1M dòng ta đang ở 386 MB, còn xa.
Khi tới lúc đó, partition theo `RANGE (created_at)` và khoá chính thành `(created_at, id)`
— nhớ rằng mọi khoá ngoại trỏ vào `submissions` sẽ phải mang thêm cột `created_at`.

`audit_log` đã có sẵn hàm `create_audit_log_partition(DATE)` và một partition `DEFAULT`.
⚠️ Job hàng tháng phải tạo partition **trước** khi sang tháng: khi partition `DEFAULT` đã
chứa dòng của tháng đó thì không gắn partition thật vào được nữa. Migration tạo sẵn 3 tháng
đệm chính vì lý do này.

---

## 8 · Bảng xếp hạng: Postgres là sự thật

```
verdict mới ──► cập nhật contest_standings (theo lô mỗi 2s, không phải mỗi verdict)
                          │
                          ├──► ghi Redis sorted set  (đọc: ZREVRANGE top 50 + ZREVRANK)
                          └──► Redis chết? đọc thẳng ix_contest_standings_rank
```

- `last_applied_submission_id` trên mỗi hàng làm cho việc cập nhật lô **idempotent** —
  chạy lại một lô đã áp dụng thì không cộng điểm hai lần (frplan Quy tắc 4).
- Cùng cột đó là đầu vào của job đối soát drift (FR-CON-09): so `contest_standings` với
  tính toán lại từ `submissions` + `judge_runs`, ghi kết quả vào `standings_drift_checks`.
- **Đóng băng (FR-CON-05)** không sửa dữ liệu thật: chụp một bản sang
  `contest_standings_frozen` đúng lúc `freeze_at`, rồi phục vụ bản chụp đó cho người thường.
  Chi phí O(số thí sinh), đúng một lần. Cột `pending_after_freeze` cho phép UI hiện ô `?`
  đúng kiểu ICPC.
- **Job rebuild phải luôn tồn tại và có test** (oj-api/CLAUDE.md mục 6). Thêm một cột vào
  bảng xếp hạng mà quên dạy job rebuild dựng lại cột đó là một lỗi im lặng cho tới ngày
  Redis chết.

---

## 9 · Phân quyền: hai role, không phải một

| Role | Dùng cho | Quyền |
|---|---|---|
| `oj_migrator` | Flyway | sở hữu schema, DDL |
| `oj_app` | `oj-api` lúc chạy | DML, **không** DDL; bị REVOKE như mục 5 |

Đây là 15 phút cấu hình đổi lấy: một lỗ SQL injection lọt lưới cũng không `DROP TABLE` được,
`audit_log` append-only thành thật, và `judge_runs` bất biến thành thật.
`oj-worker` **không có role nào** — nó không có `DataSource` (bất biến #3).

Migration `V9` viết phòng thủ: nếu role chưa tồn tại thì bỏ qua phần GRANT, nên
Testcontainers và máy dev vẫn chạy được mà không cần dựng role trước.

---

## 10 · `postgresql.conf` cho host Mac M1 Max 64 GB

Postgres chỉ được chia ~3 core cùng Redis/RabbitMQ/JVM (nfrplan 2.2), nên đừng cấu hình
như thể nó có cả máy.

```conf
# --- bộ nhớ ---
shared_buffers = 4GB                 # ~1/8 RAM; host còn phải nuôi JVM và box chấm bài
effective_cache_size = 16GB          # gợi ý cho planner, không cấp phát thật
work_mem = 16MB                      # x số kết nối x số node sort — đừng tham
maintenance_work_mem = 1GB           # VACUUM và CREATE INDEX nhanh hơn hẳn

# --- ghi & bền vững ---
synchronous_commit = on              # KHÔNG tắt. R1 = 0 bài mất là tuyệt đối,
                                     # và fsync trên NVMe chỉ ~0.1-1ms, thừa sức trong 50ms
wal_compression = zstd
max_wal_size = 4GB
checkpoint_timeout = 15min
checkpoint_completion_target = 0.9

# --- SSD ---
random_page_cost = 1.1
effective_io_concurrency = 200

# --- song song: giới hạn tay, đừng để Postgres tranh core với box chấm bài ---
max_parallel_workers = 2
max_parallel_workers_per_gather = 1

# --- an toàn vận hành ---
statement_timeout = 5s               # mặc định; pool job nền ghi đè ở tầng phiên
idle_in_transaction_session_timeout = 30s
lock_timeout = 3s

# --- đo được thì mới tối ưu được (nfrplan 2.4) ---
shared_preload_libraries = 'pg_stat_statements'
track_io_timing = on
log_min_duration_statement = 500ms
log_lock_waits = on
log_autovacuum_min_duration = 0
```

Riêng bảng nóng đã đặt sẵn trong migration:

```sql
submissions  WITH (fillfactor = 85, autovacuum_vacuum_scale_factor = 0.02,
                   autovacuum_analyze_scale_factor = 0.01)
judge_queue  WITH (fillfactor = 70, autovacuum_vacuum_scale_factor = 0,
                   autovacuum_vacuum_threshold = 50)
```

`judge_queue` bị xoá/ghi liên tục trên vài trăm dòng — để mặc định thì autovacuum gần như
không bao giờ chạy trên nó và bảng phình lên bloat gấp hàng chục lần dữ liệu thật.

---

## 11 · Connection pool: tách riêng pool cho `/internal/judge/*`

Đây là chi tiết nhỏ nhưng cứu đúng lúc tệ nhất.

```yaml
oj:
  datasource:
    app:      { maximum-pool-size: 20 }   # request người dùng
    judge:    { maximum-pool-size: 6  }   # CHỈ /internal/judge/claim và /result
```

500 người nộp bài cùng lúc có thể vét sạch pool. Nếu worker dùng chung pool đó, nó không
lấy được connection để **ghi verdict** — và bài đang chấm dở sẽ bị reaper thu hồi rồi chấm
lại, làm mọi thứ tệ thêm. Hai pool tách nhau thì đường verdict không bao giờ bị đói vì
đường đọc. Số 6 khớp với số judge slot.

Cả hai pool đều chạy bằng role `oj_app`.

---

## 12 · Backup, RPO và RTO

`nfrplan.md` chốt RPO ≤ 15 phút bằng `pg_dump`. Thiết kế này giữ nguyên và **đề xuất bổ sung**:

| Lớp | Cấu hình | RPO đạt được |
|---|---|---|
| Đã chốt | `pg_dump` mỗi 15 phút → ổ ngoài + Backblaze B2 | ≤ 15 phút |
| Đề xuất thêm | WAL archiving (`archive_mode=on`, `archive_command` → cùng đích) | **gần 0** |

Với `submissions` chỉ 386 MB ở 1M dòng, WAL archiving gần như miễn phí về dung lượng, và nó
biến "mất tối đa 15 phút bài nộp" thành "mất tối đa vài giây". Vì R1 nói **0 bài mất là tuyệt đối**,
đây là chỗ đáng chi. Nhưng nó chạm con số đã chốt R4 → **cần người quyết** (mục 14).

Diễn tập restore tuần 12 vẫn bấm giờ như kế hoạch. Một backup chưa từng restore không phải backup.

---

## 13 · Test bắt buộc cho mỗi loại thay đổi schema

Ánh xạ thẳng bảng mục 6 của `CLAUDE.md`:

| Thay đổi | Test |
|---|---|
| Migration mới | Chạy trên DB rỗng **và** DB đã seed 1M dòng. Đo thời gian khoá |
| Repository mới | Testcontainers Postgres 16 — không H2. H2 không có partial index, `SKIP LOCKED`, hay `ON CONFLICT ... WHERE` |
| Query mới trên `submissions` | `EXPLAIN (ANALYZE, BUFFERS)` dán vào PR + đếm số query bằng `datasource-proxy` |
| Thêm index vào `submissions` | Chạy lại phép đo HOT ở mục 4 và dán số vào PR |
| Đụng vào `judge_queue` / ghi verdict | Chaos test: kill worker giữa chừng, 2 worker + 100 bài, kiểm 0 mất 0 trùng |
| Đụng vào bảng xếp hạng | Chạy job rebuild, so drift = 0 |

Bộ 12 ca trong `queries/smoke_test.sql` nên được chuyển thành một lớp Testcontainers và chạy
trong CI — nó bắt được đúng những lỗi mà unit test với repository giả không bao giờ bắt được.

---

## 14 · Cần người quyết — không tự làm

Sáu điểm dưới đây nằm trong danh sách "phải dừng và hỏi" của `CLAUDE.md` mục 5:

| # | Vấn đề | Phương án | Khuyến nghị |
|---|---|---|---|
| 1 | **Hoãn partition `submissions`** — plan gốc có task 6.2 | (a) partition ngay như plan · (b) hoãn, kèm điều kiện kích hoạt ở mục 7 | **(b)** — ở 1M dòng đang là 386 MB, partition lúc này chỉ làm chậm truy vấn nóng nhất và làm phức tạp mọi khoá ngoại |
| 2 | **Không lưu kết quả từng test** | (a) lưu đủ · (b) chỉ verdict + `failed_test_ordinal` + điểm subtask | **(b)** — dữ liệu đó không ai được xem (bất biến #1), và nó là ~50M dòng |
| 3 | **Khoá lạc quan chuyển sang `judge_queue`** — bất biến #7 viết `WHERE id=? AND attempt=? AND status='JUDGING'` trên `submissions` | (a) giữ nguyên chữ · (b) giữ nguyên ngữ nghĩa, đổi chỗ đặt | **(b)** — số đo mục 4. Nhưng đây là **chạm vào một bất biến**, phải được cả hai người đồng ý và ghi ADR |
| 4 | **Source người dùng lưu trong Postgres** (`source_blobs`), không phải MinIO | (a) Postgres · (b) MinIO | **(a)** — 64KB/bài, 5000 blob = 1,2 MB; và worker nhận source qua `claim` response nên vẫn không cần DataSource. Nhưng điều này **chạm `oj-contract`** → phải hỏi |
| 5 | **Thêm role `oj_app` / `oj_migrator`** | (a) một role như hiện tại · (b) hai role | **(b)** — nhưng nó đổi cấu hình deploy và `.env.example`, không phải quyết định một mình |
| 6 | **WAL archiving bổ sung cho `pg_dump`** | (a) giữ RPO 15 phút · (b) thêm WAL archiving, RPO ≈ 0 | **(b)** — nhưng R4 là con số đã chốt |

Ngoài ra, ba con số dưới đây nằm trong schema và **đổi là phải hỏi**: lease reaper 120s
(`judge_queue.lease_until`), quota AI 5/ngày (`ai_quota_usage`), giới hạn source 64KB
(`CHECK` trên `source_blobs` và `submissions`).

---

## 15 · Danh sách "KHÔNG làm" ở tầng database

Ngang tầm quan trọng với danh sách việc phải làm:

- ❌ **`COUNT(*)` trên `submissions`** — kể cả cho trang trạng thái. Đếm trên `judge_queue`.
- ❌ **`OFFSET` để phân trang** — cursor `WHERE id < :cursor` luôn, không ngoại lệ.
- ❌ **Trigger trên `submissions`** — mọi trigger đều nằm trên đường nộp bài 300ms.
- ❌ **`ON DELETE CASCADE` trỏ vào `submissions` hay `users`** — không ai bị xoá, cascade
  chỉ tạo ảo giác là xoá được.
- ❌ **Lưu tiền bằng `FLOAT`** — `cost_micro_usd BIGINT`.
- ❌ **`CREATE TYPE ... AS ENUM`** — thêm giá trị mới vướng ràng buộc transaction của Flyway.
  Dùng `TEXT` + `CHECK`.
- ❌ **`SELECT *`** — thêm một cột `TEXT` lớn là mọi truy vấn danh sách chậm đi mà không ai hay.
- ❌ **Sửa file migration đã commit** — kể cả khi mới commit 5 phút trước (bất biến #6).
- ❌ **Business logic trong stored procedure** — trừ hai hàm hạ tầng đã có
  (`set_updated_at`, `create_audit_log_partition`). Logic ở `domain`, không ở DB.
- ❌ **Cho `oj-worker` một `DataSource`** — bất biến #3. Không có role DB nào cho worker.

---

## 16 · Thứ tự migration theo mốc

| File | Mốc | Nội dung |
|---|---|---|
| `V1__nen_tang_users_languages_hosts.sql` | M1 | `users` · `languages` · `judge_hosts` · `host_benchmarks` · `system_settings` |
| `V2__problems_testcases_testdata.sql` | M1→M4 | `problems` · `tags` · `testdata_versions` · `testcases` · `sample_testcase_contents` · `rendered_statements` |
| `V3__submissions_judge_queue_judge_runs.sql` | **M1 — lõi** | `source_blobs` · `submissions` · `judge_queue` · `judge_runs` |
| `V4__subtasks_va_ket_qua_theo_nhom.sql` | M3 | `subtasks` · `subtask_dependencies` · `judge_run_subtasks` |
| `V5__auth_refresh_token_va_audit_log.sql` | M4 | `refresh_tokens` · `login_attempts` · `login_lockouts` · `audit_log` (partition) |
| `V6__contests_va_bang_xep_hang.sql` | M5 | `contests` · `contest_*` · bảng đóng băng · FK `submissions.contest_id` |
| `V7__jobs_nen_va_van_hanh.sql` | M6 | `jobs` · `job_events` · `queue_metrics` |
| `V8__ai_review.sql` | tuần 14–15 | `ai_reviews` · `ai_quota_usage` · `ai_usage_daily` |
| `V9__phan_quyen_role_ung_dung.sql` | M6 | GRANT/REVOKE cho `oj_app` |
| `R__seed_du_lieu_tham_chieu.sql` | mọi lúc | 3 ngôn ngữ · máy chấm chuẩn · tag. Thêm ngôn ngữ = sửa file này |

M1 chỉ cần `V1`–`V3`. Đúng tinh thần "M1 vẫn là toàn bộ dự án".

---

## Báo cáo

```
File đã tạo:      migration/V1..V9 · migration/R__seed_du_lieu_tham_chieu.sql
                  queries/duong_nong.sql · queries/smoke_test.sql · postgres-design.md
Bất biến bị chạm: #7 (khoá lạc quan đổi chỗ đặt sang judge_queue — ngữ nghĩa giữ nguyên,
                  lý do và số đo ở mục 4, cần người duyệt — mục 14 điểm 3)
SLO có thể ảnh hưởng: P1 P2 S3 R1 R2 R4 SEC3 (tất cả theo hướng bảo vệ, trừ R4 là đề xuất đổi)
FR liên quan:     FR-SUB-01..12 · FR-PROB-01..12 · FR-AUTH-01..08 · FR-CON-01..09
                  · FR-ADM-01..06 · FR-AI-01..09
Test đã thêm:     12 ca trong queries/smoke_test.sql (đã chạy trên PostgreSQL 16.13)
                  + đo kích thước và EXPLAIN ở 1.000.000 dòng
Cần người quyết:  6 mục ở mục 14 + 3 con số đã chốt nằm trong schema
```
