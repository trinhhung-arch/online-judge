# Trình tự viết code — Online Judge v1.0

> **Tài liệu này trả lời đúng một câu hỏi: *gõ file nào trước file nào, và vì sao.***
>
> Nó phục tùng bốn tài liệu đã có. Khi mâu thuẫn, chúng thắng:
> `CLAUDE.md` (12 bất biến) · `nfrplan.md` (SLO) · `frplan.md` (FR) · `postgres-design.md` (schema).
>
> Cách dùng: mỗi mốc là một mục. Trong mục, các **Bước** phải làm đúng thứ tự — thứ tự đó
> là chiều phụ thuộc, không phải sở thích. Trong một Bước, các file có thể viết song song.
> Ô ⛔ là điểm dừng bắt buộc: không được đi tiếp khi chưa qua.

---

## PHẦN 0 — Bảy điều phải chốt **trước khi gõ dòng code đầu tiên**

Sáu điểm đầu là mục 14 của `postgres-design.md`; điểm thứ bảy là xung đột phát hiện khi
rà chéo lịch với `CLAUDE.md`. Mỗi điểm ghi rõ **chốt muộn nhất khi nào** và **đổi sau tốn gì**.

| # | Quyết định | Khuyến nghị | Chốt muộn nhất | Đổi sau tốn gì |
|---|---|---|---|---|
| **A** | **Khoá lạc quan đặt ở `judge_queue`, không ở `submissions`** (chạm bất biến #7) | **Theo `postgres-design.md` — giữ ngữ nghĩa, đổi chỗ đặt.** Số đo HOT update 100% vs 0% là quyết định | **Trước Bước M1-1** | Đổi chữ ký `JudgeQueueRepository`, viết lại `RecordJudgeResultUseCase` và toàn bộ chaos test |
| **B** | **Source người dùng lưu ở Postgres (`source_blobs`), worker nhận source qua `claim` response** (chạm `oj-contract`) | **Postgres.** 64KB × 5000 bài = 1,2 MB. Worker vẫn không cần `DataSource` | **Trước Bước M1-1** | `oj-contract` đã đóng băng — đổi là PR chạm cả hai vùng, cả hai người phải dừng việc |
| **C** | **Không lưu kết quả từng test** | **Không lưu.** Chỉ verdict + `failed_test_ordinal` + điểm subtask | Trước Bước M1-2 (V3) | Đổi `JudgeResultDto` + thêm bảng 50M dòng + migration trên bảng đã có dữ liệu |
| **D** | **Hai role DB `oj_app` / `oj_migrator`** | **Hai role.** 15 phút đổi lấy append-only *thật* | M0 (`.env.example`, `docker-compose.yml`) — **không phải M6** | Sửa cấu hình deploy trên host đang chạy giữa tuần 11 |
| **E** | **Hoãn partition `submissions`** (bỏ task 6.2 plan gốc) | **Hoãn**, kèm điều kiện kích hoạt: >20 GB **hoặc** autovacuum >10 phút | Trước M6 | Chỉ là bỏ một task — rẻ nhất trong bảng |
| **F** | **WAL archiving bổ sung `pg_dump`** (chạm R4 đã chốt = 15 phút) | **Thêm.** R1 nói *0 bài mất là tuyệt đối*; RPO 15 phút mâu thuẫn chính R1 | Trước tuần 12 (diễn tập restore) | Diễn tập restore phải làm lại |
| **G** | 🆕 **M1 chấm bài nhưng `isolate` chỉ có ở M2** — xem ô dưới | **M1 không thực thi mã người dùng** | **Trước Bước M1-9** | Một `ProcessBuilder` "tạm" sẽ sống tới tuần 9 và là vi phạm bất biến #4 |

> ### ⛔ Điểm G — cái bẫy lớn nhất của lịch trình
>
> M1 (tuần 1–2) phải cho ra một vòng nộp-bài → verdict chạy được. `isolate` chỉ có ở M2
> (tuần 3–4), và bản ARM phải build trong VM Linux trên Mac. Bản năng sẽ là viết một
> `ProcessBuilder("g++", ...)` "chỉ để thử vòng lặp". Đó là **bất biến #4**, và trong thực tế
> nó không bao giờ bị xoá — nó chỉ bị quên.
>
> **Cách đi đúng:** M1 **không chạy một dòng mã lạ nào**. Interface `JudgeRunner` có hai hiện thực:
>
> - `ScriptedJudgeRunner` (M1) — đọc dòng đầu source: `// EXPECT: AC` → trả `AC`;
>   `// EXPECT: TLE` → ngủ quá hạn rồi trả `TLE`; `// EXPECT: CRASH` → chết giữa chừng để
>   test reaper. Không compile, không exec.
> - `IsolateJudgeRunner` (M2) — hiện thực thật.
>
> M1 vẫn chứng minh được đúng thứ nó cần chứng minh: `accept ≠ process`, reaper, khoá lạc quan,
> 2 worker không chấm trùng, P2 < 300ms. Không thứ nào trong đó cần chạy mã người lạ.
>
> `IsolateJudgeRunner` thay chỗ vào **đúng ngày 14/14 test tấn công xanh trong CI**, không sớm hơn một giờ.

Ngoài ra, **ba con số nằm trong schema, đổi là phải hỏi người**: lease reaper `120s` ·
quota AI `5/ngày` · giới hạn source `64KB`. Đặt cả ba vào `application.yml` ngay ở M0
(`oj.judge.lease-seconds`, `oj.ai.daily-quota`, `oj.submission.max-source-bytes`) để sau này
không ai đi tìm chúng trong code.

---

## PHẦN 1 — Bốn nguyên tắc chi phối thứ tự

**1. Hợp đồng trước, hiện thực sau.** `oj-contract` là ranh giới giữa hai người *và* hai tiến trình.
Nó được viết **trong một buổi, cả hai ngồi cùng**, rồi đóng băng. Từ giây phút đó A và B
làm song song mà không chặn nhau. Đây là lý do `oj-contract` là Bước 1 của M1, không phải Bước 5.

**2. Test bất biến trước code bất biến.** ArchUnit viết ở M0 khi chưa có module nào —
lúc đó nó rẻ và luôn xanh. Viết ở tuần 5 thì nó đỏ 200 chỗ và bị disable. Y hệt với 14 test
tấn công: viết trước `IsolateJudgeRunner`, không phải sau.

**3. Cái không sửa được đứng trước cái sửa được.** Thứ tự trong một mốc luôn là:
`contract → migration → domain → port → use-case → infrastructure → controller → UI`.
Đi ngược chiều này nghĩa là bạn để framework quyết định hình dạng của domain.

**4. Không viết "bản tạm sẽ thay sau" cho thứ có seam rõ ràng.** Nếu đã biết chỗ nào sẽ đổi
(`JudgeRunner`, `JudgeEventPublisher`, `CurrentUserProvider`, `RateLimiter`), hãy **đặt interface
ngay từ lần đầu** và cho nó một hiện thực tối giản. Bản tạm *có interface* là 30 phút;
bản tạm *không có interface* là hai ngày viết lại ở tuần 7.

Bốn seam sẽ đổi hiện thực trong đời dự án — khai báo interface ngay ở M1:

| Interface | M1 | Đổi thành | Khi |
|---|---|---|---|
| `JudgeRunner` | `ScriptedJudgeRunner` | `IsolateJudgeRunner` | M2 |
| `JudgeEventPublisher` | `NoopPublisher` (chỉ log) | `RabbitJudgeEventPublisher` | M6 |
| `CurrentUserProvider` | `FixedDevUserProvider` | `JwtCurrentUserProvider` | M4 |
| `RateLimiter` | `AlwaysAllow` | `RedisRateLimiter` + fallback Postgres | M4 |

---

## PHẦN 2 — M0 · Bộ khung (tuần 0, cả hai)

Không có FR nào ở đây. Toàn bộ là Maintainability và Compatibility — và tất cả đều thuộc loại
"rẻ bây giờ, rất đắt về sau".

| Bước | Viết gì | Ghi chú |
|---|---|---|
| **0.1** | `.gitattributes` → `* text=auto eol=lf` | **File đầu tiên của repo.** Hai máy Windows/WSL2, một host macOS — sai dòng này là mọi diff về sau đều nhiễu |
| **0.2** | `pom.xml` gốc: parent + 3 module `oj-contract`, `oj-api`, `oj-worker`. Java 21, Spring Boot 3.3 BOM | Bật virtual threads: `spring.threads.virtual.enabled=true` |
| **0.3** | `oj-contract/pom.xml` — **không dependency nào ngoài JDK** | Bất biến: nó là biên giới. Một `jackson-annotations` lọt vào đây là bắt đầu của kết thúc |
| **0.4** | `docker-compose.yml`: postgres:16 · redis:7 · rabbitmq:3-management · minio | `restart: unless-stopped` ngay từ đầu |
| **0.5** | `.env.example` + `EnvVarStartupCheck` — **crash lúc boot nếu thiếu secret** | Bao gồm `OJ_DB_APP_PASSWORD`, `OJ_DB_MIGRATOR_PASSWORD` (quyết định **D**), `OJ_INTERNAL_SHARED_SECRET` |
| **0.6** | `application.yml`: toàn bộ ngưỡng có tên. ⚠️ **KHÔNG dùng `server.servlet.context-path=/api/v1`** — nó bọc *toàn bộ* ứng dụng, nên `/internal/judge/*` sẽ thành `/api/v1/internal/judge/*` và lọt ra tunnel cùng phần công khai, đúng thứ `oj-api/CLAUDE.md` mục 5 cấm. Đặt `/api/v1` trong `@RequestMapping` của từng controller công khai | Không magic number nào rời khỏi file này · thêm `oj.datasource.{app,judge}` (hai pool) |
| **0.7** | **`ArchitectureTest.java`** — 4 luật cứng + 3 luật NFR | Xem dưới. Viết khi chưa có module nào → luôn xanh, luôn có hiệu lực |
| **0.8** | `.github/workflows/ci.yml`: `./mvnw verify` + `docker buildx --platform linux/amd64,linux/arm64` | Multi-arch **từ commit đầu** (nfrplan Phần 11 tuần 0) |
| **0.9** | `logback-spring.xml` — JSON có cấu trúc + `traceId` | |
| **0.10** | `README.md` khung + `docs/adr/001..008` (danh sách ở `nfrplan.md` 8.3) | 15 phút/file. Viết trước khi quên lý do |

**Bảy luật ArchUnit của M0** (`oj-api/src/test/java/dev/oj/ArchitectureTest.java`):

```
1. dev.oj..domain..            không import org.springframework.., jakarta.persistence.., com.fasterxml..
2. dev.oj.<X>..                không import dev.oj.<Y>.infrastructure..
3. chiều module một chiều:     identity → problems → judging → contests ; ai → judging
4. dev.oj.contract..           không import gì ngoài java..
5. không class nào             gọi String.concat/format/+ để dựng SQL (chống injection)
6. không class nào             gọi System.out / System.err
7. chỉ dev.oj.ai..             được import client LLM
```

> ⛔ **DoD M0:** `./mvnw verify` xanh · CI < 10 phút · `docker compose up` cho 4 service khoẻ ·
> người kia clone repo về máy mình và chạy được (đây là bản nháp của M1-nfr "người thứ 3 dựng lại < 30 phút").

---

## PHẦN 3 — M1 · Lõi (tuần 1–2) — **chi tiết tới từng file**

> `frplan.md` Phần 6: *"Lõi. Không có FR nào khác được chen vào đây."*
> FR bắt buộc: **FR-SUB-02, 03, 04** + FR-PROB-01 tối giản. Chấm hết.

### Bước M1-1 · `oj-contract` — cả hai ngồi cùng, xong là đóng băng

Đây là buổi làm việc quan trọng nhất của cả dự án. Sau buổi này A và B không chặn nhau nữa.

```
dev.oj.contract/
  Verdict.java              enum AC WA TLE MLE RE CE IE
  JudgeJobDto.java          submissionId · attempt · traceId
                            · languageCode · compileCommand · runCommand
                            · compileTimeLimitMs · compileMemoryKb
                            · timeLimitMs · memoryLimitKb · outputLimitKb
                            · sourceContent · sourceSha256          ← quyết định B
                            · checkerType · checkerEpsilon
                            · scoringMode · testdataVersion · testdataManifestSha256
                            · testcases: List<TestcaseMetaDto>
  TestcaseMetaDto.java      ordinal · isSample · inputSha256 · outputSha256 · subtaskOrdinal
  JudgeResultDto.java       submissionId · attempt · verdict · score · maxScore
                            · failedTestOrdinal · testsRun · timeMs · memoryKb
                            · compileLog · isolateStatus · hostName · hostFactor · startedAt
  ClaimRequestDto.java      hostName · arch · freeSlots
  JudgeProgressDto.java     submissionId · attempt · fromOrdinal · toOrdinal
                            · List<TestOutcome>      ← khai báo ngay, dùng ở M3 (lô 20 test)
```

**Ba điều tuyệt đối không có trong `oj-contract`:** nội dung testcase (chỉ sha256 — bất biến #1) ·
bất kỳ annotation framework nào · bất kỳ kiểu nào của `oj-api` hay `oj-worker`.

> ⛔ Sau bước này, đổi bất cứ thứ gì ở đây là **một PR chạm cả hai vùng, cả hai người duyệt**
> (`CLAUDE.md` mục 5.1). Đừng đóng băng khi chưa chắc — nhưng đóng băng xong thì tôn trọng nó.

### Bước M1-2 · Migration V1 · V2 · V3

Chép nguyên văn từ bộ file đã có vào `oj-api/src/main/resources/db/migration/`, cộng `R__seed_du_lieu_tham_chieu.sql`.
M1 chỉ cần **V1–V3** (`postgres-design.md` mục 16). Không tạo V4–V9 lúc này.

Kiểm ngay: `docker compose exec postgres psql -d oj -f smoke_test.sql` — 12 ca, ba ca **kỳ vọng báo lỗi**.

### Bước M1-3 · `platform` — nền chung, không có nghiệp vụ

```
dev.oj.platform/
  config/     AppProperties.java          @ConfigurationProperties("oj") — mọi ngưỡng
              DataSourceConfig.java       HAI pool: app(20) + judge(6)   ← postgres-design mục 11
              JdbcConfig.java             JdbcClient bean cho từng pool
  error/      DomainException.java        base — không ai ném RuntimeException trần
              ApiError.java               record trả ra HTTP, KHÔNG chứa stack trace
              GlobalExceptionHandler.java @RestControllerAdvice
  trace/      TraceIdFilter.java          sinh/nhận traceId → MDC
  security/   CurrentUserProvider.java    interface (seam)
              FixedDevUserProvider.java   M1: trả user id 1 đã seed
              Role.java                   USER · SETTER · ADMIN
  web/        CursorPage.java             record(items, nextCursor) — không có totalCount
```

> **Vì sao hai pool ngay ở M1:** 500 người nộp cùng lúc vét sạch pool chung → worker không lấy
> được connection để **ghi verdict** → reaper thu hồi bài đang chấm dở → mọi thứ tệ thêm.
> Tách pool là một class ở tuần 1; là một buổi debug ở tuần 12.

### Bước M1-4 · `problems` tối giản (FR-PROB-01)

```
dev.oj.problems/
  domain/          Problem.java            Java thuần: id code title
                                           timeLimitMs memoryLimitKb outputLimitKb
                                           checkerType checkerEpsilon scoringMode
                                           feedbackLevel status currentTestdataVersion
                   ProblemStatus.java · CheckerType.java · FeedbackLevel.java
  application/port/     ProblemRepository.java      findPublishedByCode · findPublishedById
                        JudgeSpecRepository.java    findJudgeSpec — port RIÊNG, không gộp vào
                                                    ProblemRepository: hai đường chạy trên hai
                                                    connection pool khác nhau (app 20 / judge 6)
  application/usecase/  GetProblemUseCase.java
  infrastructure/  JdbcProblemRepository.java
  api/             ProblemController.java  GET /api/v1/problems/{code}
                   ProblemResponse.java    DTO — không bao giờ trả entity ra HTTP
```

M1 không có tạo/sửa đề qua API — seed một đề `A-PLUS-B` bằng SQL. Tạo đề là M4.

> ⚠️ `feedback_level` có mặt trong domain **ngay từ M1** dù chưa dùng. Nó là biện pháp chống
> rò rỉ testdata, không phải tính năng — thêm sau nghĩa là có một khoảng thời gian hệ thống
> không có nó (`frplan.md` Phần 6: *tuyệt đối không cắt FR-PROB-07*).

### Bước M1-5 · `judging.domain` — Java thuần, test bằng JUnit trần

```
dev.oj.judging.domain/
  Submission.java          record bất biến: id userId problemId contestId languageId
                           sourceSha256 sourceBytes createdAt status attempt
                           testdataVersion outcome judgedAt hiddenAt hiddenBy
                           markJudging(attempt) · markDone(outcome, judgedAt)
                           · markQueued() · isHidden()
  SubmissionStatus.java    QUEUED · JUDGING · DONE + bảng chuyển trạng thái
  SourceBlob.java          sha256 content byteSize — CHECK 64KB ở cả domain lẫn DB;
                           toString() KHÔNG chứa content (bất biến #9)
  JudgeOutcome.java        verdict score maxScore failedTestOrdinal timeMs memoryKb
                           — dùng chung giữa Submission (ảnh chụp) và JudgeRun (lịch sử)
  JudgeQueueEntry.java     submissionId priority attempt enqueuedAt claimedAt
                           claimedByHost leaseUntil ieRetryCount
  JudgeRun.java            bản ghi bất biến của một attempt; hostName (KHÔNG phải
                           judge_hosts.id — worker gửi tên, infrastructure tra id)
  JudgingException.java    lỗi nghiệp vụ của module, có publicMessage riêng
  DomainRules.java         hằng số nghiệp vụ có tên

KHÔNG có `Verdict.java` riêng của domain: dùng thẳng `dev.oj.contract.Verdict`.
oj-contract chỉ phụ thuộc JDK nên domain import nó vẫn qua cả bốn luật ArchUnit,
`name()` đúng bằng giá trị trong `CHECK (verdict IN (...))`, và bảy verdict là từ vựng
chung của hai tiến trình. Định nghĩa lại rồi viết mapper là nghi lễ — và nghi lễ nào
cũng có ngày lệch nhau. Cùng lý do với CheckerType/ScoringMode ở `problems.domain`.
```

Bất biến sống trong domain, không trong controller:
`markDone` từ chối verdict null · `attempt` chỉ tăng · `Submission` không có phương thức `delete()`.

**Test trước khi đi tiếp:** `SubmissionTest`, `SourceBlobTest` — JUnit thuần, không Spring context, chạy < 1s.

### Bước M1-6 · `judging.application` — port trước, use-case sau

**Port** (interface — hiện thực ở Bước M1-7):

```
SubmissionRepository       insert(NewSubmission) → id
                           findForRequester(id, requesterId, role)   ← IDOR nằm TRONG query
                           listForUser(userId, filter, cursor, size)
                           lastSubmittedAt(userId)
                           markJudging(id, attempt) · markDone(id, attempt, outcome, judgedAt)
                           markQueued(ids)   ← reaper cần; thiếu nó thì bài đã về hàng đợi
                                               mà trang chi tiết vẫn hiện "đang chấm" mãi mãi
SourceBlobRepository       saveIfAbsent(SourceBlob)
JudgeQueueRepository       enqueue(submissionId, priority)
                           claim(hostName, leaseSeconds) → Optional<ClaimedJob>
                           releaseWithOptimisticLock(submissionId, attempt)
                               → Optional<ReleasedSubmission>     ← quyết định A
                             Trả DỮ LIỆU chứ không phải boolean: judge_runs cần
                             language_id + testdata_version, hai cột nằm ở submissions.
                             `DELETE ... USING submissions ... RETURNING` lấy luôn cả hai;
                             boolean thì phải thêm một SELECT vào đúng transaction ngắn
                             nhất và nóng nhất của hệ thống.
                           reapExpired() → List<Long>
                           retryIe(submissionId, attempt, maxRetries) → boolean
                           queueDepth() → QueueStats
JudgeRunRepository         insertIfAbsent(JudgeRun) → boolean
JudgeJobPublisher          publishEnqueued(submissionId)          ← seam, M6 thành RabbitMQ
LanguageRepository         findEnabledByCode(code) → Optional<Language>
                             KHÔNG có trong bản kế hoạch đầu, nhưng SubmitSolutionUseCase
                             bắt buộc phải validate "language enabled" — không có port thì
                             không hỏi được. Chỉ phục vụ đường app; đường claim lấy lệnh
                             biên dịch qua JOIN ngay trong câu claim.
```

**Use-case** — mỗi file một class, method ≤ 50 dòng:

```
1. SubmitSolutionUseCase        ★ file quan trọng nhất hệ thống
2. ClaimJudgeJobUseCase
3. RecordJudgeResultUseCase     ★ chứa khoá lạc quan
4. ReapStaleJobsUseCase
5. GetSubmissionUseCase
6. ListMySubmissionsUseCase
```

`SubmitSolutionUseCase` — chuỗi bắt buộc, đúng thứ tự này (`oj-api/CLAUDE.md` mục 1):

```java
validate(source ≤ 64KB, language enabled, problem PUBLISHED)
sha256 = Sha256.of(source)
// ---- @Transactional, ngân sách 50ms ----
sourceBlobs.saveIfAbsent(...)                      // ON CONFLICT DO NOTHING
id = submissions.insert(...)                       // status = QUEUED
queue.enqueue(id, PRIORITY_LIVE)                   // priority 0
// ---- COMMIT ----
try { events.publishEnqueued(id); }                // NGOÀI transaction
catch (Exception e) { log.warn(...); }             // publish hỏng KHÔNG rollback — reaper nhặt
return new SubmissionAccepted(id, QUEUED);
```

Trong file này **không được xuất hiện**: `.get()` · `.join()` · `Thread.sleep` · một lời gọi worker ·
`COUNT(*)` · render Markdown · MinIO · LLM. Nếu review thấy bất kỳ thứ nào — dừng PR.

`RecordJudgeResultUseCase` — khoá lạc quan là câu **đầu tiên**:

```java
// ---- @Transactional ----
if (!queue.releaseWithOptimisticLock(id, attempt)) return IGNORED;  // 0 dòng → im lặng bỏ qua
judgeRuns.insertIfAbsent(run);                                      // PK chặn trùng lớp 2
submissions.markDone(...);                                          // HOT update
// ---- COMMIT ----
```

Nhánh `IE` (FR-SUB-12) rẽ **trước** câu trên: `queue.retryIe(id, attempt, 2)` trả `true` thì
kết thúc, không ghi verdict — bài quay lại hàng đợi.

**Test trước khi đi tiếp** — unit với fake repository + fake publisher:

| Test | Kiểm |
|---|---|
| `SubmitSolutionUseCaseTest` | Trả về trong khi publisher **chưa** được gọi xong · publisher ném lỗi vẫn trả 202 · không lời gọi nào tới worker |
| `RecordJudgeResultUseCaseTest` | Lock trả false → **không** ghi gì · gọi hai lần → verdict không đổi |
| `ReapStaleJobsUseCaseTest` | Lease hết hạn → về QUEUED, `attempt` **không** tăng ở đây |

### Bước M1-7 · `judging.infrastructure` — chép SQL, đừng viết lại

```
JdbcSubmissionRepository.java      truy vấn 1b · 2 (phần submissions) · 3c · 6 · 7 · 9
JdbcSourceBlobRepository.java      truy vấn 1a
JdbcJudgeQueueRepository.java      truy vấn 1c · 2 · 3a · 3' · 4 · 5 · 12
JdbcJudgeRunRepository.java        truy vấn 3b
NoopJudgeEventPublisher.java       chỉ log — M6 thay bằng RabbitMQ
StaleJobReaper.java                @Scheduled(fixedDelayString="${oj.judge.reaper-interval}")
ContractMapper.java                domain ↔ oj-contract
```

> **Chép nguyên văn từ `docs/sql/duong_nong.sql`.** Mười hai câu đó đã được đo và kiểm chứng
> trên Postgres 16.13 ở 1.000.000 dòng. Viết lại "cho gọn" là cách nhanh nhất mất `SKIP LOCKED`,
> mất `RETURNING`, hoặc thêm một `COUNT(*)`. Dùng `JdbcClient` + named parameter, không nối chuỗi.

**Test:** Testcontainers Postgres 16 (**không H2** — H2 không có partial index, `SKIP LOCKED`,
`ON CONFLICT ... WHERE`). Chuyển 12 ca `smoke_test.sql` thành `SchemaInvariantsIT` chạy trong CI.

### Bước M1-8 · `judging.api`

```
SubmissionController.java       POST /api/v1/submissions   → 202 {submissionId,"QUEUED"}   FR-SUB-01,02
                                GET  /api/v1/submissions/{id}                          FR-SUB-03,04
                                GET  /api/v1/submissions?cursor=&limit=  (20, trần 50) FR-SUB-07
InternalJudgeController.java    POST /internal/judge/claim
                                POST /internal/judge/result
InternalSecretFilter.java       shared secret từ env — KHÔNG phải JWT người dùng
```

> `/internal/judge/*` **không nằm dưới `/api/v1/`** và **không được lộ ra tunnel**
> (`oj-api/CLAUDE.md` mục 5). Cấu hình Cloudflare Tunnel chỉ publish `/api/v1/**` — kiểm bằng tay ở tuần 9.

### Bước M1-9 · `oj-worker` (Người B — song song từ Bước M1-1)

```
dev.oj.worker/
  WorkerApplication.java       KHÔNG có starter-data-jdbc trong pom  ← bất biến #3
  api/     JudgeApiClient.java RestClient, chỉ 2 endpoint, shared secret, retry backoff
  loop/    JudgeLoop.java      long-lived, thread pool = judge_slots, PULL không poll rỗng
           ResultBuffer.java   API không phản hồi → giữ lại + backoff, KHÔNG vứt kết quả
  run/     JudgeRunner.java        ★ interface
           ScriptedJudgeRunner.java  M1 — KHÔNG thực thi mã người dùng (điểm G)
  config/  WorkerProperties.java  hostName · slots · leaseSeconds
```

> ⛔ **Kiểm bất biến #3 bằng CI, không bằng lời hứa:** thêm một test đọc `oj-worker/pom.xml`
> và fail nếu thấy `spring-boot-starter-data-jdbc`, `postgresql`, `flyway`, `lettuce`, `minio`.

### Bước M1-10 · Bộ test đóng mốc M1

| Loại | Test | Kiểm |
|---|---|---|
| Chaos | `KillWorkerMidJudgeIT` | Sau 120s bài về `QUEUED`, được chấm lại, `attempt`=2 |
| Chaos | `TwoWorkersNoDoubleJudgeIT` | 2 worker + 20 bài → **0 bài chấm 2 lần** |
| Chaos | `KillApiDuringSubmitIT` | Đã commit thì còn; chưa commit thì user thấy lỗi rõ ràng |
| Chaos | `PublishFailsButReaperRecoversIT` | Publisher ném lỗi → bài vẫn được chấm (đây là *lý do reaper tồn tại*) |
| Perf | `SubmitLatencyTest` | P2 < 300ms, đo bằng Micrometer, có `traceId` xuyên chặng |
| Arch | `WorkerHasNoDataSourceTest` | Bất biến #3 |

> ⛔ **DoD M1 — không đi tiếp khi thiếu bất kỳ dòng nào:**
> nộp bài trả 202 < 300ms · verdict đến sau qua polling · kill worker giữa chừng thì tự chấm lại ·
> 2 worker 20 bài không trùng · `traceId` xuyên API → queue → worker → kết quả ·
> `./mvnw verify` xanh · **chưa có một dòng mã người dùng nào được thực thi**.

---

## PHẦN 4 — M2 · Sandbox (tuần 3–4, Người B)

Không có FR mới. Đây là mốc thuần chất lượng, và là **rủi ro #1 của cả dự án**.

| Bước | Viết gì |
|---|---|
| **2.1** | `scripts/build-isolate-arm.sh` — build `isolate` **trong VM Linux ARM trên Mac**, không copy binary từ WSL |
| **2.2** | ★ **14 test tấn công trước** — `oj-worker/src/test/resources/attacks/*` + `SandboxAttackIT`. Danh sách ở `nfrplan.md` 4.1 |
| **2.3** | `IsolateCommandBuilder` — cgroup v2 · **không** `--share-net` · fs read-only trừ `/box` · uid riêng · giới hạn output |
| **2.4** | `CompileStep` — **biên dịch cũng trong box** (compiler bomb là có thật) |
| **2.5** | `IsolateMetaParser` — đọc file `meta` → verdict. Mã lạ → `IE`, **không map bừa sang `RE`** |
| **2.6** | `BoxPool` — số box = `judge_slots` cố định theo config (**không** theo số core). Dọn box trong `finally` |
| **2.7** | `TestdataFetcher` — tải theo `sha256`, cache cục bộ. **Testdata không nằm trong box** — input chỉ qua stdin |
| **2.8** | tmpfs cho box dir (8GB RAM disk) |
| **2.9** | `HostBenchmarkJob` — chạy lúc worker khởi động + mỗi 15 phút → ghi `host_benchmarks`, cập nhật `host_factor`, alert khi drift > 8% |
| **2.10** | 🆕 Deploy thử lên Mac (nfrplan Phần 11 tuần 2) + ghi baseline vào README |

> ⛔ **Cổng chuyển:** `IsolateJudgeRunner` chỉ được đăng ký thay `ScriptedJudgeRunner` khi
> **14/14 test tấn công xanh trong CI**. Từ đó, mọi PR chạm sandbox chạy lại **toàn bộ 14 test**,
> kể cả PR "chỉ là refactor".

---

## PHẦN 5 — M3 · Realtime · đa ngôn ngữ · tối ưu (tuần 5–6)

**Người B — đường chấm:**

| Bước | Viết gì | Lợi ích |
|---|---|---|
| 3.1 | `LanguageRegistry` đọc bảng `languages`; worker nhận `compileCommand`/`runCommand` qua contract | M4-nfr: thêm ngôn ngữ = 1 dòng `R__seed`, 0 dòng code |
| 3.2 | `Checker` interface + `ExactChecker` · `TokenChecker` · `FloatChecker(epsilon)` | **FR-PROB-05** · thêm checker = 1 class |
| 3.3 | Migration **V4** + `SubtaskScorer` (MIN/SUM + `subtask_dependencies`) | FR-PROB-06 |
| 3.4 | Early exit khi test đầu fail (tắt khi có subtask) | −50% thời gian trung bình |
| 3.5 | PCH cho `bits/stdc++.h` | compile 1.5s → 0.35s |
| 3.6 | `CompileCache` theo `sha256(source+lang+flags)` | Hit rate cao trong contest |
| 3.7 | Gửi kết quả **theo lô 20 test** → dùng `JudgeProgressDto` | −20× round-trip. **Chạm contract → PR hai vùng** |

**Người A — realtime:**

| Bước | Viết gì |
|---|---|
| 3.8 | `SubmissionEventBus` interface + **`RedisPubSubEventBus` ngay** (xem cảnh báo dưới) |
| 3.9 | `GET /api/v1/submissions/{id}/stream` — SSE, **virtual threads**, timeout, heartbeat · **FR-SUB-05** |
| 3.10 | **Fallback REST polling** — không có fallback thì tính năng chưa xong (`oj-api/CLAUDE.md` mục 4) |
| 3.11 | `VerdictExplainer` — 7/7 verdict giải thích được (U3): CE→log compiler · TLE→`2.03s / 2.00s` · RE→`SIGSEGV — thường do truy cập mảng ngoài phạm vi` · IE→mã sự cố |
| 3.12 | ★ `RuntimeFormatter` — **làm tròn thời gian chạy đến 10ms**, kèm chú thích *"đo trên máy chấm chuẩn, sai số ±5%"*. Chữ số hàng mili giây là **nhiễu, không phải thông tin** (P7): hiển thị nó tạo ra một trò chơi giả — người dùng nộp lại 10 lần để "tối ưu" 23ms→21ms, tiêu 10 lượt chấm cho 0 giá trị · **FR-SUB-11** |

> ⚠️ **Một chỗ tôi đề nghị lệch khỏi `nfrplan.md`.** Bản đó đặt "SSE fan-out qua Redis pub/sub"
> ở tuần 7 (M4). Nhưng Redis đã có trong `docker-compose` từ M0, và chi phí là như nhau
> (~4h) dù làm ở tuần 5 hay tuần 7. Làm bản in-memory ở M3 rồi thay ở M4 là **viết hai lần**
> và có hai tuần mà chạy 2 instance API là 50% user mất realtime.
> **Khuyến nghị: làm thẳng Redis pub/sub ở Bước 3.8.** Đây là một quyết định nhỏ — nhưng nên ghi ADR.

---

## PHẦN 6 — M4 · Danh tính · quyền · upload · giao diện (tuần 7–9)

> **Mốc nặng nhất: 19 FR, ước lượng thực ~95h, trong khi cả team có ~100h/3 tuần.**
> Kín lịch, không còn khoảng trống (`frplan.md` Phần 6).

**Thứ tự bắt buộc — bảo mật trước giao diện.** Nếu tuần 8 thấy chậm, thứ cắt là giao diện,
không phải phân quyền.

| Bước | Viết gì | FR |
|---|---|---|
| **4.1** | Migration **V5** | |
| **4.2** | `identity.domain`: `User` · `Role` · `RefreshToken` (thuần Java) | |
| **4.3** | `identity.application`: `RegisterUser` · `Login` · `RefreshSession` · `Logout` · `ChangePassword` · `AnonymizeAccount` | FR-AUTH-01..05, 07 |
| **4.4** | `identity.infrastructure`: BCrypt cost 12 · `JdbcUserRepository` · `JdbcRefreshTokenRepository` (**lưu sha256 của token, không lưu token thô**) | SEC2 |
| **4.5** | `platform.security`: `JwtService` (15 phút) · `JwtAuthFilter` · **`JwtCurrentUserProvider` thay `FixedDevUserProvider`** | FR-AUTH-02, S1/S2 |
| **4.6** | ★ **`@RequiresRole` + aspect kiểm ở tầng use-case** + **luật ArchUnit thứ 8**: mọi use-case sửa dữ liệu phải mang annotation này | FR-AUTH-06, bất biến #11 |
| **4.7** | `RateLimiter` → `RedisRateLimiter` + fallback Postgres (truy vấn 7) · `LoginLockout` | FR-SUB-08, FR-AUTH-08 |
| **4.8** | ★ **Rà IDOR toàn bộ repository** — điều kiện chủ sở hữu **trong câu query**, không phải `if` ở service. Test: vai trò sai → **403, không phải 200 rỗng** | SEC |
| **4.9** | `problems` đầy đủ: tạo/sửa/xuất bản · `feedback_level` · danh sách phân trang 50 · render Markdown+LaTeX server-side + cache `rendered_statements` | FR-PROB-01,02,07,08,09 |
| **4.10** | `ZipTestdataValidator` + đánh dấu từng test **sample / hidden**: ≤200MB nén · ≤2GB giải nén · tỉ lệ ≤100:1 · ≤1000 test · chặn `..`, đường dẫn tuyệt đối, symlink · **validate `problem.yaml` trước khi ghi bất cứ file nào** → **job nền có tiến độ** | FR-PROB-03, 04, SEC2 |
| **4.11** | `MinioTestdataStore` — content-addressed, chỉ ở `problems.infrastructure` | |
| **4.12** | Giao diện (A): CodeMirror 6 · **nháp trong localStorage** · trang chi tiết bài nộp · thông báo lỗi tiếng người · mobile-responsive · **a11y mức A** | FR-SUB-06,10, U |

> ⚠️ **Xung đột thứ tự phát hiện khi rà chéo.** Bước 4.10 (upload ZIP) là *job nền có tiến độ*
> theo Quy tắc 5 của `frplan.md`. Nhưng hạ tầng job (`jobs`, `job_events`, migration **V7**)
> nằm ở M6. Ba cách:
> **(a)** Kéo V7 + `JobRunner` lõi lên **tuần 7**, trước Bước 4.10 — thêm ~6h vào mốc đã kín;
> **(b)** M4 upload chạy đồng bộ với giới hạn 20MB, mở lên 200MB ở M6 — vi phạm FR-PROB-03 trong 3 tuần;
> **(c)** Đẩy Bước 4.10 sang M6.
> **Khuyến nghị (a)** — hạ tầng job còn được rejudge, rebuild leaderboard và AI review dùng lại,
> nên 6h đó được trả lại ba lần. Đây là **điểm cần hai người quyết**.

**Tuần 9 — ba việc không phải code, đừng cắt:**
buổi tấn công chéo 3h mỗi người · usability test đợt 1 (3 người ngoài, **rồi im lặng**) ·
hoán đổi vùng (A làm một task của B) · Cloudflare Tunnel + domain, người ngoài truy cập được.

---

## PHẦN 7 — M5 · Kỳ thi (tuần 10–12, Người A)

| Bước | Viết gì | FR |
|---|---|---|
| 5.1 | Migration **V6** | |
| 5.2 | `contests.domain`: `Contest` · `ContestFormat` interface · `IcpcFormat` · `IoiFormat` | FR-CON-01, 06 · M4-nfr (thêm thể thức = 1 file) |
| 5.3 | ★ `ContestWindowService.isProblemInRunningContest(problemId)` — **một câu, ba nơi dùng**: cấm sửa đề (FR-PROB-11) · tắt AI review (FR-AI-02) · cấm rejudge (FR-ADM-01) | |
| 5.4 | `ContestAccessPolicy` — đề chỉ truy cập trong khung giờ, **kiểm ở use-case, không phải ẩn nút** | FR-CON-03 |
| 5.5 | `RegisterForContest` | FR-CON-02 |
| 5.6 | `StandingsUpdater` — **theo lô mỗi 2 giây**, idempotent theo `last_applied_submission_id` | FR-CON-04, P8 |
| 5.7 | `RedisStandingsCache` (`ZREVRANGE` top 50 + `ZREVRANK` quanh mình) + `PostgresStandingsReader` (degraded) | P1, P8 |
| 5.8 | `FreezeStandingsJob` — chụp sang `contest_standings_frozen` đúng lúc `freeze_at`; `pending_after_freeze` cho ô `?` kiểu ICPC | FR-CON-05 |
| 5.9 | `RebuildStandingsJob` — **job nền có tiến độ**, dùng truy vấn 11 | FR-CON-08 |
| 5.10 | `StandingsDriftCheckJob` + metric `drift` + alert | FR-CON-09 |
| 5.11 | `RevealAfterEnd` — mở đề, **mở lại AI review**, công bố bảng đầy đủ | FR-CON-07 |

> Bất biến của mốc này: **Redis là cache, `contest_standings` là sự thật.** Mọi giá trị trong Redis
> phải dựng lại được 100% từ Postgres. Thêm một cột vào bảng xếp hạng mà quên dạy `RebuildStandingsJob`
> dựng lại cột đó là một lỗi im lặng cho tới ngày Redis chết.
>
> FR-CON-10 (virtual participation) là **ứng viên bị cắt đầu tiên**.

---

## PHẦN 8 — M6 · Vận hành (tuần 10–12, Người B chủ trì)

| Bước | Viết gì | FR / NFR |
|---|---|---|
| 6.1 | Migration **V7** (nếu chưa kéo lên tuần 7) + **V9** (role `oj_app`) | quyết định D |
| 6.2 | Hạ tầng job: `Job` · `JobRunner` · `JobProgress` · `GET /api/v1/jobs/{id}` · **sống sót restart** · `ux_jobs_one_active_per_type` | Quy tắc 5 |
| 6.3 | ★ **`RejudgeJob`**: hai hàng đợi `judge.live` (ưu tiên 0) / `judge.rejudge` (ưu tiên 10) · trần **30% năng lực**, tự giảm về 0 khi `queue_wait` live > 5s · **cấm chạy khi có contest đang diễn ra** · verdict cũ giữ nguyên, tạo `attempt` mới | FR-ADM-01, P4, P6 |
| 6.4 | ★ **Chuyển Postgres queue → RabbitMQ**: quorum queue · **manual ack sau khi kết quả đã vào DB** · `prefetch=1` · DLQ sau 3 lần | R |
| 6.5 | `audit_log` write path + `AuditLogController` (chỉ ADMIN, phân trang) + job tạo partition hàng tháng | FR-ADM-02 |
| 6.6 | `AdminUserController` — đổi vai trò, vô hiệu hoá (không xoá cứng) | FR-ADM-03 |
| 6.7 | `/actuator/health` **thật**: Postgres · RabbitMQ · Redis · số worker sống | A |
| 6.8 | **Graceful shutdown worker** (SIGTERM → chấm nốt → nack phần còn lại → dọn box → thoát) | A3 |
| 6.9 | Degraded mode 5 kịch bản (`nfrplan.md` 7.2) | A |
| 6.10 | Micrometer P1–P8 + dashboard vận hành: độ dài hàng đợi · thời gian chờ · worker sống · tỉ lệ IE · drift · chi phí LLM | FR-ADM-04 |
| 6.11 | Trang trạng thái công khai (truy vấn 12 — đếm trên `judge_queue`, **không** `COUNT(*)` trên `submissions`) | FR-ADM-05 |
| 6.12 | Kill switch `submissions.accepting` — bài đang chấm vẫn chấm xong | FR-ADM-06 |
| 6.13 | `HideSubmission` (ADMIN ẩn, không xoá) + FR-SUB-12 hoàn chỉnh | FR-SUB-09, 12 |
| 6.14 | FR-PROB-10/11/12: sửa testdata → cảnh báo + bắt buộc job rejudge + `audit_log`; cấm sửa đề trong contest đang chạy; tải testdata chỉ SETTER/ADMIN | |
| 6.15 | Index audit + chặn N+1 bằng `datasource-proxy` đếm query trong integration test | P1 |

> **Phần thưởng của thiết kế M1 nằm ở Bước 6.4.** Đổi transport chỉ chạm `JudgeEventPublisher`
> và consumer. Không một use-case nào, không một câu SQL nào phải sửa — vì `judge_queue` mới là
> sự thật và RabbitMQ chỉ là đường dẫn. Nếu bạn thấy Bước 6.4 đụng vào nhiều hơn hai file,
> nghĩa là ở đâu đó M1 đã làm sai.
>
> Bài test quan trọng nhất sau bước này: **kill RabbitMQ** — API vẫn nhận bài, reaper nhặt lại khi queue sống lại.

---

## PHẦN 9 — Tuần 12–13 · Kiểm chứng và đệm

**Tuần 12 — không viết tính năng, chỉ chứng minh:**

- Toàn bộ **9 kịch bản chaos test** (`nfrplan.md` 5.2)
- **Load test k6** 3 kịch bản: 500 submit đồng thời · 2 bài/s trong 30 phút · 1000 kết nối SSE
- **Test 3 node**: worker chạy trên Mac + WSL của A + WSL của B → throughput ~3× (rẻ và rất thuyết phục)
- **Diễn tập restore có bấm giờ**, mục tiêu RTO ≤ 30 phút — **không được cắt**
- Usability test đợt 2

**Tuần 13 — đệm:** checklist OWASP ký nhận · hoàn tất 8 file ADR · tài liệu NFR · video demo.

> Nếu tuần 12 phải cắt việc, cắt từ **Usability** và **Availability**.
> Đừng bao giờ cắt bộ test tấn công, chaos test, hay buổi diễn tập restore.

---

## PHẦN 10 — Tuần 14–15 · AI Code Reviewer

> Chỉ chạy nếu chọn **phương án C** (`nfrplan.md` 10.7). Ước lượng ~34h ≈ 1 tuần công cả team.

| Bước | Viết gì | Ràng buộc |
|---|---|---|
| 7.1 | Migration **V8** | |
| 7.2 | `ai` module tách hoàn toàn — **luật ArchUnit thứ 7 đã chặn gọi LLM từ ngoài package này từ M0** | AI1 |
| 7.3 | `CodeReviewer` interface + `OpenAiCodeReviewer` — đổi nhà cung cấp = thay 1 class | M |
| 7.4 | `prompts/code-review-v1.md` **trong file**, version-controlled. Lưu `model` + `prompt_version` cùng mỗi review | M |
| 7.5 | ★ `PromptBuilder` — prompt **chỉ chứa**: đề bài (public) · source của chính user · verdict · test số mấy fail. **KHÔNG BAO GIỜ** nội dung testdata, lời giải mẫu, source người khác | SEC3, rủi ro #3 |
| 7.6 | ★ **8 test prompt injection** trong CI + kiểm output (review giống system prompt hoặc giống testdata → chặn, log, alert) | AI3 |
| 7.7 | `ConsumeAiQuotaUseCase` — **một câu `INSERT ... ON CONFLICT ... WHERE`** (truy vấn 8). Không `SELECT` → `if` → `UPDATE` | FR-AI-03 |
| 7.8 | Queue riêng priority thấp + worker AI — **0ms thêm vào đường chấm** | AI1 |
| 7.9 | Circuit breaker (5 lỗi → tắt 5 phút) · timeout cứng 30s · retry backoff tối đa 2 · DLQ. LLM lỗi → **không trừ quota, verdict không bị ảnh hưởng** | **FR-AI-08**, AI1 |
| 7.10 | `CostMeter` — token vào/ra, chi phí ước tính, **circuit breaker ngân sách ngày**, alert ở 80% | AI2 |
| 7.11 | `ReviewCache` theo `sha256(source)` — review được lưu; xem lại **không gọi LLM, không trừ quota** | **FR-AI-06**, AI2, Quy tắc 4 |
| 7.12 | `AiReviewGate` — **tắt trong contest**, kiểm ở use-case theo `contest.status` | FR-AI-02 |
| 7.13 | Kill switch `ai_review.enabled` (đã có trong `system_settings` từ V1) | FR-AI-09 |
| 7.14 | UI: nút "Nhận góp ý AI" · số lượt còn lại · streaming qua SSE đã có · 👍/👎 · **"Góp ý từ AI — có thể sai"** | FR-AI-01,04,07 |
| 7.15 | Ràng buộc nghiệp vụ trong prompt + **test kiểm chứng**: không đưa mã giải hoàn chỉnh | FR-AI-05 |

---

## PHẦN 11 — Vi-thứ-tự: viết một PR bất kỳ theo đúng chín bước này

Áp dụng cho mọi thay đổi từ tuần 1 tới tuần 15. Mất 2 phút để nhớ, cứu hầu hết lỗi thiết kế.

```
0. Trả lời 6 câu ở CLAUDE.md mục 4          (lộ gì · có danh sách không · chạy 2 lần
                                             · quá 5s không · chạm contest không · thêm chặng
                                             vào đường 2 giây không)
1. Chạm oj-contract?      → DỪNG, hỏi người. Nếu có, PR chạm hai vùng, hai người duyệt
2. Migration              → file V<n+1> MỚI. Không sửa file đã commit, kể cả commit 5 phút trước
3. domain + JUnit trần    → bất biến sống ở đây, không ở controller
4. port (interface)       → application định nghĩa cái nó cần, không nhận cái infrastructure có
5. use-case + test fake   → @RequiresRole nếu sửa dữ liệu
6. infrastructure + IT    → Testcontainers, không H2. EXPLAIN dán vào PR nếu chạm bảng nóng
7. controller + test 403  → vai trò sai phải 403, KHÔNG phải 200 rỗng
8. cập nhật frplan.md / docs/adr nếu hành vi hoặc kiến trúc đổi
9. dán khối báo cáo CLAUDE.md mục 9 vào mô tả PR
```

**Nhánh:** `a/<viec>` hoặc `b/<viec>`, sống ≤ 3 ngày. **Đổi tên file luôn dùng `git mv`.**

---

## PHẦN 12 — Song song hoá A ↔ B

Điểm đồng bộ duy nhất là `oj-contract`. Sau khi nó đóng băng ở tuần 1, hai người gần như không chặn nhau.

| Tuần | Người A | Người B | Điểm gặp |
|---|---|---|---|
| 0 | pom, CI, ArchUnit, docker-compose | ADR, README, buildx | Cả hai |
| 1 | **`oj-contract` (cùng nhau)** → platform, problems, judging.api | **`oj-contract` (cùng nhau)** → judging.domain/application/infrastructure, oj-worker | ★ đóng băng contract |
| 2 | Reaper, chaos test phía API | `ScriptedJudgeRunner`, JudgeLoop, ResultBuffer | DoD M1 |
| 3–4 | Deploy thử lên Mac, script deploy | **Sandbox + 14 test tấn công** | Cổng 14/14 |
| 5–6 | SSE + Redis pub/sub, VerdictExplainer | Checker, subtask, early exit, PCH, lô 20 test | Bước 3.7 chạm contract |
| 7–9 | Auth, JWT, quyền, IDOR, giao diện | Upload ZIP + validate, MinIO, LanguageRegistry | ★ tuần 9: tấn công chéo + hoán đổi vùng |
| 10–12 | Contest, leaderboard, freeze | RabbitMQ, jobs, rejudge, health, metric | Chaos test tuần 12 |
| 13 | Tài liệu, video | OWASP, ADR | |
| 14–15 | UI AI review, streaming | `ai` module, prompt, injection test, cost meter | |

---

## PHẦN 13 — Ba xung đột thứ tự đã phát hiện, và cách xử

| # | Xung đột | Ở đâu | Khuyến nghị |
|---|---|---|---|
| 1 | **M1 chấm bài nhưng `isolate` chỉ có ở M2** — mọi lối tắt đều vi phạm bất biến #4 | M1 vs M2 | `ScriptedJudgeRunner` — M1 không thực thi mã người dùng (Phần 0, điểm G) |
| 2 | **Upload ZIP (M4, tuần 8) cần hạ tầng job nền (V7, M6)** | Bước 4.10 | Kéo V7 + `JobRunner` lõi lên tuần 7 — nó còn được rejudge, rebuild leaderboard, AI review dùng lại |
| 3 | **`judging` phải viết trước `identity`**, nhưng chiều phụ thuộc là `identity → problems → judging` | M1 vs M4 | Seam `CurrentUserProvider` ở `platform.security`: M1 dùng `FixedDevUserProvider` (user seed id=1), M4 thay bằng `JwtCurrentUserProvider`. **Không** truyền `userId` lung tung qua tham số controller rồi sửa lại 20 chỗ ở tuần 7 |

Thêm một điểm nhỏ: **role DB `oj_app`/`oj_migrator` (quyết định D) nên vào từ M0**, không phải V9/M6 —
vì nó nằm trong `.env.example` và `docker-compose.yml`, và đổi cấu hình deploy giữa tuần 11
trên host đang chạy là loại việc không ai muốn làm.

---

## PHẦN 14 — Khi chậm thì cắt theo thứ tự này

`frplan.md` Phần 6 đã cho thứ tự cắt FR. Ở tầng code, thứ tự tương ứng:

```
cắt trước  →  FR-PROB-12 (tải testdata)
              FR-SUB-10  (nháp localStorage)
              FR-PROB-06 (subtask) + Bước 3.3
              FR-CON-10  (virtual participation)
              đẹp giao diện (giữ a11y mức A — nó rẻ khi làm từ đầu, đắt gấp 10 khi sửa sau)
              ────────── ranh giới ──────────
KHÔNG cắt  →  FR-PROB-07 feedback_level   (chống rò rỉ testdata, không phải tính năng)
              14 test tấn công sandbox
              chaos test
              diễn tập restore có bấm giờ
              job đối soát drift (FR-CON-09)
              8 test prompt injection
```

---

## Một câu để nhớ về thứ tự

Ba thứ hệ thống này bán — **công bằng · không mất bài · an toàn** — đều được quyết định
ở **tuần 1 và tuần 3**, chứ không phải ở tuần 12.

`accept ≠ process`, khoá lạc quan, reaper, worker không có `DataSource`, ArchUnit, và
`isolate` — sáu thứ đó nếu làm đúng ở M0–M2 thì mười tuần còn lại là thêm tính năng lên
một nền vững. Nếu làm sai, chúng không phải là bug để sửa ở tuần 12 — chúng là kiến trúc
để viết lại.

Vậy nên nếu có một tuần trong lịch xứng đáng được làm chậm và kỹ, đó là **tuần 1**.

---

### Báo cáo

```
File đã tạo:      docs/build-order.md
Bất biến bị chạm: không (kế hoạch, chưa có code). Nhưng nêu rõ 3 chỗ lịch trình
                  ÉP vi phạm nếu làm theo bản năng: #4 (M1 chưa có isolate),
                  #11 (quyền ở controller khi vội ở M4), #3 (worker cần DB khi vội ở M3)
SLO có thể ảnh hưởng: không
FR liên quan:     toàn bộ FR-AUTH · FR-PROB · FR-SUB · FR-CON · FR-ADM · FR-AI
Test đã thêm:     không (kế hoạch). Bộ test bắt buộc của từng mốc liệt kê trong tài liệu
Cần người quyết:  7 điểm ở Phần 0 (A và B phải chốt TRƯỚC dòng code đầu tiên)
                  + xung đột #2 ở Phần 13 (kéo V7 lên tuần 7)
                  + đề nghị lệch nhỏ ở Bước 3.8 (Redis pub/sub ở M3 thay vì M4)
```
