# Cấu trúc source code — Online Judge v1.0

> Tài liệu này trả lời câu hỏi *"file nào nằm ở đâu và vì sao"*.
> Nó phục tùng `CLAUDE.md` (12 bất biến), `nfrplan.md` (SLO), `frplan.md` (FR),
> `postgres-design.md` (schema). Khi mâu thuẫn, bốn tài liệu kia thắng.

---

## 0 · Một nguyên tắc chi phối

Cấu trúc thư mục không phải chuyện thẩm mỹ. Ở dự án này nó có đúng một nhiệm vụ:

> **Làm cho việc vi phạm 12 bất biến trở thành một dòng `import` nhìn thấy được trong diff,
> và bị ArchUnit chặn trước khi con người phải nhớ ra.**

Ba ví dụ cụ thể của nguyên tắc đó:

| Bất biến | Cấu trúc ép nó thế nào |
|---|---|
| #3 — worker không có `DataSource` | `oj-worker` là một Maven module **không** khai báo `spring-boot-starter-jdbc`. Muốn vi phạm phải sửa `pom.xml` — mà sửa `pom.xml` là việc phải hỏi người (CLAUDE.md 5.2) |
| #4 — mọi mã người dùng chạy trong `isolate` | Chỉ package `worker.sandbox` được phép `new ProcessBuilder`. ArchUnit rule mới, xem mục 6 |
| #10 — không gọi LLM ngoài package `ai` | `ai` là một module riêng ở `oj-api`, ArchUnit đã có rule (nfrplan 8.2) |

---

## 1 · Toàn cảnh repo

```
online-judge/
├── CLAUDE.md                       ← luật gốc
├── pom.xml                         ← parent aggregator, KHÔNG có code
├── .gitattributes                  ← eol=lf, chống lệch Windows/WSL/macOS
├── .env.example
│
├── oj-contract/                    ← biên giới giữa 2 người, 2 tiến trình. Chỉ JDK.
├── oj-api/                         ← modular monolith
├── oj-worker/                      ← nơi duy nhất chạy mã người lạ
├── oj-web/                         ← frontend (build riêng, không phải Maven module)
│
├── docker-compose.yml              ← postgres · redis · rabbitmq · minio (Ở GỐC REPO,
│                                     không phải trong infra/ — `docker compose up` phải
│                                     chạy được ngay sau khi clone, không cần -f)
├── infra/
│   ├── postgres/init/01-roles.sql  ← tạo oj_app / oj_migrator (quyết định D)
│   ├── docker-compose.host.yml     ← chồng lên khi chạy trên Mac host        (chưa có)
│   ├── isolate/Dockerfile          ← worker + isolate, build được cả amd64 lẫn arm64
│   ├── prometheus/ · grafana/                                                (M6)
│   └── scripts/                    ← backup.sh · restore.sh · deploy.sh      (M6)
│
├── scripts/
│   ├── build-isolate.sh            ← build isolate TỪ NGUỒN trên chính máy chấm (Bước 2.1)
│   └── mount-box-tmpfs.sh          ← box dir lên tmpfs (Bước 2.8)
│
├── docs/
│   ├── frplan.md · nfrplan.md · postgres-design.md · build-order.md
│   ├── cau-truc-source.md          ← file này
│   ├── adr/                        ← 001..010, xem nfrplan 8.3
│   └── sql/
│       ├── duong_nong.sql          ← 12 truy vấn nóng, chép nguyên văn vào repository
│       ├── smoke_test.sql          ← 12 ca kiểm chứng schema
│       └── migration-cho-moc-sau/  ← V4..V9, CHƯA nằm trong db/migration (xem README)
│
└── .github/workflows/
    ├── ci.yml                      ← mvnw verify + ArchUnit + JaCoCo, < 10 phút (M3)
    ├── sandbox-attack.yml          ← 14 test tấn công, mỗi push (SEC1) — ĐÃ BẬT ở M2
    └── buildx.yml                  ← multi-arch amd64 + arm64 (C1)
```

**Vì sao `oj-web` không phải Maven module:** đóng gói frontend vào JAR làm CI chậm và làm
mọi thay đổi CSS phải build lại backend. Deploy riêng, phục vụ qua nginx trong compose.

---

## 2 · `oj-contract` — nhỏ nhất, quan trọng nhất

```
oj-contract/src/main/java/dev/oj/contract/
├── JudgeJobDto.java        submissionId · attempt · traceId · languageCode
│                           · compileCommand · runCommand · compileTimeLimitMs
│                           · compileMemoryKb · timeLimitMs · memoryLimitKb
│                           · outputLimitKb · sourceFileName · sourceContent · sourceSha256
│                           · checkerType · checkerEpsilon · scoringMode
│                           · testdataVersion · testdataManifestSha256
│                           · testcases: List<TestcaseMetaDto>          + Builder
├── JudgeResultDto.java     submissionId · attempt · verdict · score · maxScore
│                           · failedTestOrdinal · testsRun · timeMs · memoryKb
│                           · compileLog · isolateStatus · hostName · hostFactor
│                           · startedAt · subtasks
├── JudgeProgressDto.java   lô 20 test — khai báo từ M1, dùng ở M3 (BATCH_SIZE = 20)
├── HostBenchmarkDto.java   phép đo tốc độ máy chấm (M2) — hostName · arch · measuredAt
│                           · cpuTimeMs · hostFactor · calibrated · driftPct
├── JudgeEndpoints.java     bốn đường /internal/judge/* + tên header X-Internal-Secret
├── ClaimRequestDto.java    hostName · arch · freeSlots
├── TestcaseMetaDto.java    ordinal · isSample · inputSha256 · outputSha256 · subtaskOrdinal
├── SubtaskResultDto.java   điểm từng nhóm (M3)
├── Verdict.java            enum: AC WA TLE MLE RE CE IE
├── CheckerType.java        enum EXACT/TOKEN/FLOAT, code() khớp CHECK chữ thường trong DB
├── ScoringMode.java        enum ALL_OR_NOTHING / SUBTASK
├── Sha256.java             MỘT cách băm duy nhất, dùng chung hai tiến trình
├── ContractChecks.java     package-private — kiểm & cắt bớt
└── package-info.java       liệt kê ba endpoint nội bộ
```

**Ba khác biệt so với bản phác đầu tiên, và mỗi cái có lý do:**

1. **`JudgeJob` không mang `feedbackLevel`.** Worker *luôn* trả về số thứ tự test sai; việc
   người dùng có được thấy con số đó hay không là quyết định của API theo
   `problems.feedback_level` (FR-PROB-07). Một trường không được truyền đi thì không thể bị
   truyền nhầm — đây là bất biến #1 ép ở tầng kiểu dữ liệu.
2. **`sourceContent` nằm trong job** (quyết định B). Worker nhận source qua response của
   `claim` nên vẫn không cần `DataSource`. Testdata thì vẫn chỉ đi bằng `sha256`.
3. **`JudgeEndpoints.java` giữ bốn đường dẫn `/internal/judge/*` và tên header
   `X-Internal-Secret`.** Trước đó `InternalSecretFilter` và `JudgeApiClient` mỗi bên tự gõ
   một chuỗi giống nhau — lệch một ký tự thì trình biên dịch im, test hai bên vẫn xanh vì mỗi
   bên dùng hằng của chính mình, và triệu chứng duy nhất là mọi request từ worker nhận 401.
4. **`JudgeJobDto.sourceFileName`** — `Main.` + `languages.source_extension`, do API tính.
   `g++` nhận biết ngôn ngữ **qua phần mở rộng**, và `java` bắt buộc lớp `Main` nằm đúng trong
   `Main.java`. Không có trường này thì worker phải giữ một bảng tra `languageCode -> tên file`
   của riêng nó — hai nguồn sự thật cho cùng một dữ kiện, và "thêm 1 ngôn ngữ = 1 dòng config,
   0 dòng code" (chỉ số M4) không còn đúng.
5. **`HostBenchmarkDto` + `POST /internal/judge/benchmark`** — worker không có `DataSource`
   (bất biến #3), nên trước đây không có đường nào để một phép đo tới được bảng
   `host_benchmarks` (đã tồn tại từ `V1`). Lịch sử hiệu chuẩn chỉ nằm trong log và mất khi
   worker khởi động lại; sau một kỳ thi thì câu *"máy chấm hôm đó có chậm không"* không có gì
   để trả lời.
6. **`JudgeJobDto.maxScore`** — điểm tối đa do API tính (`JudgeSpec.maxScore()`), worker chỉ
   dùng lại. Trước đó `JudgeResultDto` bắt buộc có `maxScore` mà job không mang, nên worker
   phải tự bịa ra 100: luật tính điểm sống ở hai nơi, và ở M3 (subtask) thì bịa không nổi.

**Bốn luật của module này:**

1. `pom.xml` **không có một dependency nào**. Không Jackson, không Lombok, không Spring.
   Serialize là việc của hai bên, không phải của hợp đồng.
2. Chỉ `record` và `enum`. Không logic, không validate, không constructor thông minh.
3. **Không có trường nào chứa được nội dung testcase.** `JudgeJob` mang
   `testdataManifestSha256`, không mang testdata — worker tự tải từ MinIO. Đây là bất biến #1
   được ép ở tầng kiểu dữ liệu.
4. Đổi bất cứ thứ gì ở đây → **dừng và hỏi người**, hai phía đổi trong cùng một PR
   (CLAUDE.md 5.1).

---

## 3 · `oj-api` — modular monolith

### 3.1 · Bố cục chung

```
oj-api/src/main/java/dev/oj/
├── OjApiApplication.java
├── platform/          ← hạ tầng dùng chung, không thuộc nghiệp vụ nào
├── identity/          ┐
├── problems/          │  5 module nghiệp vụ, mỗi module 4 tầng
├── judging/           │
├── contests/          │
└── ai/                ┘
```

Chiều phụ thuộc (ArchUnit ép, vi phạm = fail CI):

```
identity ◀── problems ◀── judging ◀── contests
                            ▲
                            └── ai

platform ◀── tất cả (kể cả ai)
```

Đọc là: `contests` **được phép** import `judging`; `judging` **không biết** `contests` tồn tại.

### 3.2 · Bốn tầng trong mỗi module

```
dev.oj.<module>/
├── domain/                 Java thuần. Không Spring, không JPA, không Jackson.
│   ├── <Entity>.java       Entity KHÔNG BAO GIỜ rời khỏi đây (CLAUDE.md mục 7)
│   ├── <ValueObject>.java
│   └── <Module>Exception.java
│
├── application/
│   ├── usecase/            ★ nơi kiểm quyền (bất biến #11), một class một use-case
│   ├── port/               interface ra ngoài: repository, queue, clock, storage
│   └── published/          ★ DUY NHẤT thứ module khác được import
│
├── infrastructure/         JdbcClient, RabbitTemplate, RedisTemplate, MinIO client
│                           implement các interface ở application/port/
│
└── api/                    Controller + DTO. DTO định nghĩa ở đây, không ở domain.
```

**Package `published` là phát minh đáng giá nhất của bố cục này.** Không có nó, "module X
không import `infrastructure` của Y" là một rule yếu — người ta sẽ import
`Y.application.usecase.SomeUseCase` và bạn vừa tạo ra một khớp nối không ai định.

Với nó, ArchUnit viết được một câu chặt:

```java
noClasses().that().resideOutsideOfPackage("dev.oj.judging..")
    .should().dependOnClassesThat()
    .resideInAPackage("dev.oj.judging..")
    .andShould().dependOnClassesThat()
    .resideOutsideOfPackage("dev.oj.judging.application.published..");
```

`published` chỉ chứa **interface truy vấn read-only + record DTO**. Ví dụ
`JudgingQueries.findSubmissionSummary(id)` cho `ai` dùng — `ai` không bao giờ chạm vào
`SubmissionRepository`.

### 3.3 · `platform/` — hạ tầng dùng chung

```
dev.oj.platform/
├── config/            OjProperties (mọi ngưỡng có tên), DataSourceConfig (2 pool!)
├── error/             GlobalExceptionHandler, ApiError — không lộ stack trace ra client
├── security/          JwtFilter, InternalSecretFilter, @RequiresRole, CurrentUser
├── web/               Cursor, Page<T>, CursorPageArgumentResolver
├── observability/     TraceIdFilter (MDC), metrics, /actuator/health thật
├── ratelimit/         RateLimiter (Redis, fallback Postgres)
├── audit/             AuditLogger — port + impl, mọi module gọi
├── settings/          SystemSettings — kill switch AI, chế độ bảo trì
├── jobs/              JobRunner, JobLease, JobProgress — KHUNG, không phải job cụ thể
├── messaging/         RabbitPublisher, RedisEventBus (pub/sub cho SSE fan-out)
└── sse/               SseHub, SseEmitterRegistry — virtual threads
```

Bốn thứ **bắt buộc** nằm ở `platform` chứ không ở module nghiệp vụ, vì cả 5 module đều cần
và chiều phụ thuộc cấm chúng gọi ngang nhau:

| Thứ | Vì sao ở platform |
|---|---|
| `audit/` | `problems` ghi audit, `judging` cũng ghi. Đặt ở `judging` là ép `problems → judging` — ngược chiều |
| `jobs/` | Khung job nền dùng chung; **job cụ thể nằm ở module sở hữu nó** (xem 3.5) |
| `sse/` | Fan-out qua Redis chung cho cả trang submission lẫn leaderboard (oj-api/CLAUDE.md 4) |
| `settings/` | Kill switch AI (FR-AI-09) do ADMIN bấm, nhưng `ai` không được là chủ của công tắc tắt chính nó |

**`DataSourceConfig` có 2 pool** — `app` (20) và `judge` (6), theo postgres-design.md mục 11.
Đây là chỗ duy nhất trong code biết về sự tồn tại của hai pool.

### 3.4 · `judging/` — chi tiết, vì đây là lõi

```
dev.oj.judging/
├── domain/
│   ├── Submission.java             trạng thái QUEUED/JUDGING/DONE, attempt
│   ├── Verdict.java                (ánh xạ sang contract.Verdict ở tầng api)
│   ├── JudgeQueueEntry.java
│   └── JudgingException.java
│
├── application/
│   ├── usecase/
│   │   ├── SubmitSolutionUseCase.java      ★ ĐƯỜNG NÓNG — ngân sách 300ms
│   │   ├── ClaimJudgeJobUseCase.java       idempotent theo attempt
│   │   ├── RecordJudgeResultUseCase.java   khoá lạc quan trên judge_queue
│   │   ├── GetSubmissionUseCase.java       ★ M3 áp FeedbackPolicy (problems/domain)
│   │   │                                    detailById() — chỗ DUY NHẤT lọc đường REST
│   │   ├── ListMySubmissionsUseCase.java   cursor-based, ≤50
│   │   ├── ReapStaleJobsUseCase.java       mỗi 15s, lease 120s
│   │   └── RejudgeJobUseCase.java          priority=10, implement platform/jobs (M6)
│   ├── port/
│   │   ├── SubmissionRepository.java
│   │   ├── JudgeQueueRepository.java
│   │   ├── SourceBlobRepository.java
│   │   ├── JudgeJobPublisher.java  ← RabbitMQ ẩn sau interface này
│   │   └── ProgressPublisher.java  ← Redis pub/sub ẩn sau interface này
│   └── published/
│       ├── JudgingQueries.java     cho ai/ và contests/
│       └── SubmissionSummary.java
│
├── infrastructure/
│   ├── JdbcSubmissionRepository.java     ← SQL nguyên văn từ docs/sql/duong_nong.sql
│   ├── JdbcJudgeQueueRepository.java
│   ├── RabbitJudgeJobPublisher.java
│   └── RedisProgressPublisher.java
│
└── api/
    ├── SubmissionController.java         /api/v1/submissions
    ├── SubmissionSseController.java      /api/v1/submissions/{id}/stream
    ├── internal/
    │   └── InternalJudgeController.java  ★ /internal/judge/* — KHÔNG dưới /api/v1
    └── dto/
```

**Ba chi tiết không được đổi:**

1. `internal/` là package riêng để cấu hình security lọc theo package prefix, và để
   `grep -r "internal"` ra đúng bề mặt cần bảo vệ. Endpoint này không nghe trên tunnel.
2. `SubmitSolution` là class **duy nhất** trong repo có comment ghi ngân sách thời gian ở đầu
   file. Ai thêm một lời gọi I/O vào đó sẽ đọc thấy nó trước.
3. `FeedbackPolicy` nằm ở **`problems/domain`**, không phải `judging/domain` như bản phác đầu
   tiên của file này. Lý do: `feedback_level` là một cột của `problems` và là quyết định của
   **đề**, không phải thuộc tính của bài nộp. Đặt nó ở `judging` thì module chấm bài trở thành
   nơi cất luật hiển thị của module đề — và ngày M4 thêm "nội dung test mẫu khi
   `SAMPLE_DETAIL`", luật ấy phải nằm ở cả hai chỗ.
   Nó vẫn là Java thuần, không Spring, nên **unit-test được không cần context**
   (`FeedbackPolicyTest`), và `judging` import được vì chiều module là `problems → judging`.

### 3.5 · Job nền nằm ở đâu

Khung ở `platform/jobs/`, **việc cụ thể ở module sở hữu dữ liệu**:

| Job (bảng `jobs.type`) | Class | Module |
|---|---|---|
| `REJUDGE` | `RejudgeJob` | `judging` |
| `TESTDATA_IMPORT` | `TestdataImportJob` | `problems` |
| `LEADERBOARD_REBUILD` | `LeaderboardRebuildJob` | `contests` |
| `STANDINGS_DRIFT_CHECK` | `StandingsDriftCheckJob` | `contests` |
| `HOST_BENCHMARK` | `HostBenchmarkJob` | `platform` (không thuộc nghiệp vụ nào) |

### 3.6 · Resources

```
oj-api/src/main/resources/
├── application.yml              ★ mọi ngưỡng/timeout/giới hạn có tên ở đây
├── application-dev.yml · application-host.yml
├── db/migration/                ← V1..V9 + R__seed (postgres-design.md mục 16)
└── prompts/
    └── code-review-v3.md        ← nfrplan 10.6: prompt trong file, có version
```

`application.yml` là nơi sống của các con số ở CLAUDE.md 5.4 — judge slot, reaper 120s,
rate limit 10s, quota AI 5/ngày, ZIP 200MB, source 64KB. **Không một con số nào trong bảng
đó được xuất hiện dưới dạng literal trong code Java.**

---

## 4 · `oj-worker`

```
oj-worker/src/main/java/dev/oj/worker/
├── OjWorkerApplication.java
├── config/
│   ├── WorkerProperties.java       số slot, đường dẫn isolate, tmpfs, batch size
│   └── GracefulShutdown.java       ★ SIGTERM (oj-worker/CLAUDE.md mục 5)
│
├── client/                         ★ NƠI DUY NHẤT nói chuyện với oj-api
│   ├── JudgeApiClient.java         claim + result, shared secret từ env
│   └── ResultBuffer.java           giữ kết quả khi API không phản hồi, retry backoff
│
├── pipeline/
│   ├── JudgeLoop.java              vòng lặp claim → execute → report
│   ├── JobExecutor.java            vòng đời một job, dọn box trong finally
│   └── SlotPool.java               số slot CỐ ĐỊNH theo config, không theo số core
│
├── sandbox/                        ★ NƠI DUY NHẤT được spawn process
│   ├── IsolateBox.java             init / run / cleanup, AutoCloseable
│   ├── IsolateCommand.java         dựng tham số: no-net, ro fs, cg limits
│   ├── IsolateMeta.java            parse file meta; mã lạ → IE, không map bừa sang RE
│   ├── CommandTemplate.java        {bin}/{src}/{dir}/{mem} → argv; tra argv[0] vì isolate
│   │                               dùng execve chứ KHÔNG tra PATH (ADR 010 mục 4)
│   └── SandboxException.java       sandbox hỏng ≠ bài nộp hỏng → luôn ra IE
│
├── compile/
│   ├── Compiler.java               biên dịch TRONG box (bất biến #4)
│   └── CompileCache.java           sha256(source + lang + flags)
│
├── run/
│   ├── JudgeRunner.java            ★ seam: Scripted (M1) ⇄ Isolate (M2)
│   ├── IsolateJudgeRunner.java     hiện thực M2 — bắt cả Throwable, mọi lối thoát là IE
│   ├── TestRunner.java             input qua stdin, testdata KHÔNG vào box
│   ├── OutputLimiter.java          cắt stdout NHƯNG vẫn đọc tới EOF (ADR 010 mục 2)
│   └── checker/                    Checker · Tokens · Exact · Token · Float · Checkers
│
├── testdata/
│   ├── TestdataFetcher.java        cache trước, nguồn xa sau, băm lại trước khi tin
│   ├── TestdataSource.java         seam cho MinIO (Bước 4.11)
│   ├── LocalDirectoryTestdataSource.java   hiện thực duy nhất tới M4
│   ├── TestdataUnavailableException.java   không tải được → IE, KHÔNG chấm thiếu test
│   └── ContentAddressedCache.java  hash đổi → cache tự miss, không cần invalidate
│
├── report/
│   └── BatchReporter.java          gom lô 20 test rồi mới gửi
│
└── calibration/
    └── HostBenchmark.java          đo host_factor lúc khởi động + định kỳ 15 phút;
                                    cảnh báo drift > 8% (bẫy throttle nhiệt, rủi ro #5)
```

**Còn rỗng, có chủ ý:** `pipeline/JobExecutor.java` và `pipeline/SlotPool.java` đã có nội dung
từ M2; `report/BatchReporter.java` (lô 20 test) chờ `/internal/judge/progress` ở **M3**, và
`config/GracefulShutdown.java` là **Bước 6.8**. Một file rỗng ở đây nghĩa là "đã có chỗ, chưa
tới lượt", không phải "quên".

**Hai luật của cây này:**

- Chỉ `client/` có HTTP client. Không package nào khác biết địa chỉ của `oj-api`.
- Chỉ `sandbox/` có `ProcessBuilder`. Mọi thứ khác muốn chạy gì thì đi qua `IsolateBox`.

Hai luật này biến bất biến #3 và #4 thành hai câu ArchUnit (mục 6), và biến buổi review
thành "có file mới nào ngoài hai package đó import `java.net` hay `java.lang.ProcessBuilder`
không" — một câu hỏi trả lời được trong 5 giây.

---

## 5 · Cây test — song ánh với cây source

```
oj-api/src/test/java/dev/oj/
├── architecture/
│   └── ArchitectureTest.java       ★ chạy đầu tiên trong CI
├── judging/
│   ├── application/                unit test, fake repository + fake queue
│   ├── infrastructure/             @Testcontainers Postgres 16 — KHÔNG H2
│   └── api/                        test phân quyền: vai trò sai → 403, không phải 200 rỗng
├── smoke/
│   └── SchemaInvariantTest.java    ← 12 ca của queries/smoke_test.sql
└── chaos/
    └── QueueChaosTest.java         2 worker + 100 bài, kill ngẫu nhiên, 0 mất 0 trùng

oj-worker/src/test/
├── java/dev/oj/worker/
│   ├── WorkerFixtures.java                       cấu hình test + cổng requireIsolate
│   ├── sandbox/SandboxAttackIT.java              ★ 14 case (nfrplan 4.1)
│   ├── sandbox/SandboxHarness.java               chạy file tấn công qua ĐÚNG IsolateCommand
│   ├── sandbox/{IsolateCommand,IsolateMeta,CommandTemplate}Test.java
│   │                                             nửa chạy được trên MỌI máy, kể cả macOS
│   └── run/IsolateJudgeRunnerIT.java             7 verdict + early exit + cache biên dịch
└── resources/attacks/              14 file mã tấn công, mỗi file một case
```

**Tên lớp là `SandboxAttackIT`, không phải `AttackSuiteTest`** (`build-order.md` Bước 2.2 gọi
đúng tên này). Hậu tố `IT` không phải chuyện thẩm mỹ: surefire chỉ nhận `*Test`, nên một lớp
tên `...Test` cần `isolate` thật sẽ đỏ trên mọi máy không có sandbox, còn `*IT` chạy ở
failsafe đúng chỗ của nó.

**Lớp IT sandbox FAIL chứ không SKIP khi thiếu `isolate` trên Linux.** Bỏ qua trong im lặng
biến cổng chuyển của M2 thành một lời hứa, và `nfrplan` 4.5 viết rõ "fail 1 case = fail
build". Trên macOS thì bỏ qua, vì `isolate` không tồn tại ở đó.

**Vì sao 14 test tấn công là file dữ liệu chứ không phải chuỗi trong Java:** thêm case thứ 15
là thêm một file, không phải sửa một class — và diff của PR đọc được bằng mắt.

**Vì sao `SchemaInvariantTest` tồn tại:** 12 ca trong `smoke_test.sql` bắt đúng loại lỗi mà
unit test với repository giả không bao giờ bắt được — khoá ngoại tổng hợp chặn testcase ẩn,
quota AI nguyên tử, unique job REJUDGE (postgres-design.md mục 13).

---

## 6 · ArchUnit — sáu rule, mỗi rule là một bất biến

Ba rule đầu đã có trong nfrplan 8.2; ba rule sau là đề nghị bổ sung của tài liệu này.

| # | Rule | Bất biến |
|---|---|---|
| 1 | `domain` không import Spring/JPA/Jackson | Mục 3 luật 1 |
| 2 | Module X chỉ import `dev.oj.Y.application.published..` | Mục 3 luật 2 |
| 3 | Không nối chuỗi trong SQL | #5 (SEC2) |
| 4 | Không gọi LLM ngoài package `ai` | #10 (AI1) |
| 5 | Cấm `System.out.println` | #12 |
| 6 | **`ProcessBuilder` / `Runtime.exec` chỉ trong `worker.sandbox`** | **#4 (SEC1)** |
| 7 | **HTTP client chỉ trong `worker.client`** | **#3 (S1, S2)** |

Rule 6 và 7 chạy trong module `oj-worker`, không phải `oj-api`.

---

## 7 · Quy ước đặt tên

| Loại | Quy ước | Ví dụ |
|---|---|---|
| Use-case | Động từ + danh từ + hậu tố **`UseCase`**, một class một việc | `SubmitSolutionUseCase`, `RecordJudgeResultUseCase` |
| Port | Danh từ + `Repository` / `Publisher` / `Storage` | `JudgeQueueRepository` |
| Impl | Công nghệ + tên port | `JdbcJudgeQueueRepository`, `RabbitJudgeJobPublisher` |
| DTO request | `<Việc>Request` | `SubmitSolutionRequest` |
| DTO response | `<Thứ>Response` / `<Thứ>View` | `SubmissionDetailView` |
| Migration | `V<n>__mo_ta_khong_dau.sql` | `V10__them_cot_x.sql` |
| Test | `<Class>Test` · integration `<Class>IT` | `SubmitSolutionUseCaseTest` |

> **Hậu tố `UseCase` không phải chuyện thẩm mỹ.** Luật ArchUnit thứ 8 (bật ở M4, Bước 4.6) khớp theo `haveSimpleNameEndingWith("UseCase")` để ép mọi use-case sửa dữ liệu phải mang `@RequiresRole` (bất biến #11). Bỏ hậu tố thì luật ấy khớp 0 class và **xanh vô nghĩa** — vì `archunit.properties` đặt `failOnEmptyShould=false`. Một luật xanh vô nghĩa còn tệ hơn không có luật.
| Nhánh | `a/<viec>` hoặc `b/<viec>`, sống ≤ 3 ngày | `b/isolate-cgroup` |

**Từ vựng lấy từ CLAUDE.md mục 10** — `submission` không phải `solution`, `verdict` không
phải `result`, `attempt` không phải `retry`. Tên class phải dùng đúng từ đó.

Đổi tên file: **luôn `git mv`**, không đổi trong IDE (CLAUDE.md mục 7).

---

## 8 · Thứ tự dựng cây theo mốc

Đừng tạo hết 5 module ở tuần 1 — thư mục rỗng là nợ, không phải chuẩn bị.

| Mốc | Tạo gì |
|---|---|
| **M0** | `pom.xml` cha · `oj-contract` · `platform/{config,error,observability}` · `ArchitectureTest` |
| **M1** | `judging/` đủ 4 tầng · `problems/domain` tối giản · V1–V3 |
| **M2** | `oj-worker/{sandbox,compile,run}` · 14 test tấn công |
| **M3** | `worker/{testdata,report,run/SubtaskScorer}` · `problems/domain/FeedbackPolicy` · `judging/{api/SubmissionSseController,infrastructure/RedisSubmissionEventBus}` · V4 |
| **M4** | `identity/` đủ 4 tầng · `problems/` đầy đủ · `platform/{security,ratelimit,sse}` · V5 |
| **M5** | `contests/` · `platform/messaging` (Redis pub/sub) · V6 |
| **M6** | `platform/{jobs,audit,settings}` · các job cụ thể · V7, V9 |
| **T14–15** | `ai/` · V8 · `prompts/` |

Mỗi module chỉ ra đời khi có FR đầu tiên cần đến nó.

---

## 9 · Cần người quyết

| # | Vấn đề | Khuyến nghị |
|---|---|---|
| 1 | Thêm package `application/published/` + ArchUnit rule #2 — đây là **bổ sung** cho luật "module X không import infrastructure của Y" ở CLAUDE.md mục 3 | Nhận. Không có nó, luật đó không ép được bằng máy |
| 2 | ArchUnit rule #6 và #7 ở `oj-worker` (hiện repo chưa có ArchUnit ở module này) | Nhận. Chi phí ~1h, ép trực tiếp bất biến #3 và #4 |
| 3 | `oj-web` build ngoài Maven, deploy qua nginx | Nhận, nhưng chạm `docker-compose` và script deploy → hai người cùng duyệt |
| 4 | ArchUnit ở `oj-worker` cần thêm dependency `archunit-junit5` vào `oj-worker/pom.xml` | **Sửa `pom.xml` là việc phải hỏi người** (CLAUDE.md 5.2) |

---

## Báo cáo

```
File đã tạo:      docs/cau-truc-source.md
Bất biến bị chạm: không (tài liệu này chỉ đề nghị thêm hàng rào, không nới cái nào)
SLO có thể ảnh hưởng: M (maintainability) — theo hướng cải thiện
FR liên quan:     không trực tiếp
Test đã thêm:     đề nghị 3 rule ArchUnit mới (#2, #6, #7) — chưa viết
Cần người quyết:  4 mục ở mục 9
```
