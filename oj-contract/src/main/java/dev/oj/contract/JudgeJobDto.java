package dev.oj.contract;

import java.math.BigDecimal;
import java.util.List;

/**
 * Một việc chấm bài, trả về từ {@code POST /internal/judge/claim}.
 *
 * <p>Đây là <b>tất cả</b> những gì worker được biết. Nó không có {@code DataSource},
 * không có Redis, không có MinIO client trỏ vào hạ tầng của API — nó chỉ có record này
 * và hai endpoint (bất biến #3). Nếu một nhiệm vụ có vẻ cần worker đọc DB, gần như luôn
 * có nghĩa là dữ liệu đó phải nằm ở đây.
 *
 * <h2>Hai điều dễ hiểu sai nhất trong record này</h2>
 *
 * <p><b>1. {@code timeLimitMs} và {@code memoryLimitKb} đã quy về MÁY CHẤM CHUẨN.</b>
 * API tính sẵn {@code problems.time_limit_ms * languages.time_multiplier +
 * languages.startup_overhead_ms} — vì chỉ API mới có bảng {@code languages}. Worker nhân
 * tiếp {@code host_factor} của <i>chính nó</i> — vì chỉ worker mới biết nó đang chạy trên
 * máy nào và benchmark ra bao nhiêu:
 * <pre>
 *   gioi han CPU thuc te = timeLimitMs * host_factor      (worker tu nhan)
 *   gioi han wall        = 2 * gioi han CPU               (chan ngu / cho I/O vo han)
 * </pre>
 * Ranh giới trách nhiệm này chính là thứ chặn cuộc cãi nhau "máy tao AC mà CI báo TLE"
 * ({@code nfrplan.md} 9.1). Nếu bạn thấy mình sắp nhân {@code host_factor} ở phía API —
 * dừng lại, bạn đang nhân hai lần.
 *
 * <p><b>2. Không có nội dung testcase ở đây.</b> {@link TestcaseMetaDto} chỉ mang
 * {@code sha256}; worker tải nội dung từ MinIO và <b>đưa vào chương trình qua stdin</b>,
 * không bao giờ đặt file test trong thư mục box — một chương trình bốn dòng đọc thư mục
 * là lộ toàn bộ đáp án ({@code oj-worker/CLAUDE.md} mục 1.5).
 *
 * @param submissionId           id bài nộp
 * @param attempt                lần chấm thứ mấy, luôn {@code >= 1}. Tăng ở bước
 *                               {@code claim} — chính điều đó vô hiệu hoá kết quả trả về
 *                               muộn của một worker đã bị reaper thu hồi
 * @param traceId                xuyên suốt API, queue, worker, kết quả (bất biến #12)
 * @param languageCode           {@code languages.code}: {@code cpp20}, {@code py311}, {@code java21}
 * @param compileCommand         {@code null} với ngôn ngữ thông dịch. Lệnh này cũng chạy
 *                               <b>trong sandbox</b> — compiler bomb là có thật (bất biến #4)
 * @param runCommand             lệnh chạy, lấy từ bảng {@code languages} chứ không hardcode
 *                               trong Java (M4-nfr: thêm ngôn ngữ = 1 dòng config, 0 dòng code)
 * @param compileTimeLimitMs     trần thời gian biên dịch
 * @param compileMemoryKb        trần bộ nhớ biên dịch
 * @param timeLimitMs            giới hạn CPU <b>trên máy chấm chuẩn</b> — xem ghi chú 1
 * @param memoryLimitKb          giới hạn bộ nhớ, đã cộng {@code memory_overhead_kb} của ngôn ngữ
 * @param outputLimitKb          trần stdout; chương trình in 10GB phải bị cắt, không được
 *                               làm đầy đĩa host
 * @param sourceContent          mã nguồn, tối đa {@value #MAX_SOURCE_BYTES} byte
 * @param sourceSha256           khoá của {@code source_blobs}, đồng thời là một nửa khoá
 *                               cache biên dịch của worker
 * @param checkerType            bộ so sánh output
 * @param checkerEpsilon         sai số, bắt buộc có khi và chỉ khi {@code checkerType=FLOAT}
 * @param scoringMode            quyết định worker có được early exit hay không
 * @param maxScore               điểm tối đa của bài này. <b>API tính, worker chỉ dùng lại</b> —
 *                               trước đây trường này không có và worker phải tự bịa ra 100,
 *                               tức là luật tính điểm sống ở hai nơi. Với {@code SUBTASK}
 *                               (M3) nó là tổng điểm các nhóm, và bịa thì không bịa nổi
 * @param testdataVersion        phiên bản testdata dùng cho lần chấm này; ghi lại vào
 *                               {@code judge_runs} để khi verdict hôm nay khác hôm qua thì
 *                               truy được ngay vì sao (FR-PROB-10)
 * @param testdataManifestSha256 khoá cache testdata của worker. Đề sửa testdata thì hash đổi
 *                               và cache tự động miss — không cần cơ chế invalidate nào
 * @param testcases              metadata từng test, theo thứ tự {@code ordinal}
 */
public record JudgeJobDto(
        long submissionId,
        int attempt,
        String traceId,
        String languageCode,
        String compileCommand,
        String runCommand,
        int compileTimeLimitMs,
        int compileMemoryKb,
        int timeLimitMs,
        int memoryLimitKb,
        int outputLimitKb,
        String sourceContent,
        String sourceSha256,
        CheckerType checkerType,
        BigDecimal checkerEpsilon,
        ScoringMode scoringMode,
        int maxScore,
        int testdataVersion,
        String testdataManifestSha256,
        List<TestcaseMetaDto> testcases) {

    /** FR-SUB-01 · {@code CHECK (byte_size <= 65536)} · {@code oj.submission.max-source-bytes}. */
    public static final int MAX_SOURCE_BYTES = 65_536;

    public JudgeJobDto {
        ContractChecks.requirePositive(submissionId, "submissionId");
        ContractChecks.requireAtLeast(attempt, 1, "attempt");
        ContractChecks.requireText(traceId, "traceId");
        ContractChecks.requireText(languageCode, "languageCode");
        ContractChecks.requireText(runCommand, "runCommand");
        ContractChecks.requirePositive(compileTimeLimitMs, "compileTimeLimitMs");
        ContractChecks.requirePositive(compileMemoryKb, "compileMemoryKb");
        // Cùng khoảng với CHECK trên problems.time_limit_ms / memory_limit_kb.
        ContractChecks.requireRange(timeLimitMs, 100, 30_000, "timeLimitMs");
        ContractChecks.requireRange(memoryLimitKb, 16_384, 1_048_576, "memoryLimitKb");
        ContractChecks.requirePositive(outputLimitKb, "outputLimitKb");

        ContractChecks.requireText(sourceContent, "sourceContent");
        if (ContractChecks.utf8Length(sourceContent) > MAX_SOURCE_BYTES) {
            throw new IllegalArgumentException(
                    "sourceContent vượt " + MAX_SOURCE_BYTES + " byte — lẽ ra đã bị chặn ở "
                            + "SubmitSolutionUseCase, tới được đây là có bug");
        }
        ContractChecks.requireSha256(sourceSha256, "sourceSha256");

        if (checkerType == null) {
            throw new NullPointerException("checkerType");
        }
        // Gương của ck_problems_epsilon: epsilon có khi và chỉ khi checker là float.
        boolean laFloat = checkerType == CheckerType.FLOAT;
        if (laFloat != (checkerEpsilon != null)) {
            throw new IllegalArgumentException(
                    "checkerEpsilon bắt buộc có khi và chỉ khi checkerType=FLOAT (nhận "
                            + checkerType + " / " + checkerEpsilon + ")");
        }
        if (scoringMode == null) {
            throw new NullPointerException("scoringMode");
        }
        ContractChecks.requireAtLeast(maxScore, 1, "maxScore");
        ContractChecks.requireAtLeast(testdataVersion, 1, "testdataVersion");
        ContractChecks.requireSha256(testdataManifestSha256, "testdataManifestSha256");

        testcases = ContractChecks.frozen(testcases);
        ContractChecks.requireRange(
                testcases.size(), 1, TestcaseMetaDto.MAX_ORDINAL, "testcases.size");
    }

    /** Ngôn ngữ thông dịch — không có bước biên dịch, nên không có verdict {@code CE}. */
    public boolean isInterpreted() {
        return compileCommand == null || compileCommand.isBlank();
    }

    /** Khoá cache binary đã biên dịch ({@code nfrplan.md} 2.3 mục 3). */
    public String compileCacheKey() {
        return Sha256.hexOf(sourceSha256 + ' ' + languageCode + ' '
                + (compileCommand == null ? "" : compileCommand));
    }

    /**
     * Giới hạn CPU thực tế trên máy đang chạy. Gọi ở worker, với {@code host_factor} mà
     * chính worker đó benchmark được.
     */
    public long effectiveCpuLimitMs(BigDecimal hostFactor) {
        return BigDecimal.valueOf(timeLimitMs).multiply(hostFactor).longValue();
    }

    /** Giới hạn wall bằng 2 lần CPU, chặn chương trình ngủ hoặc chờ I/O vô hạn. */
    public long effectiveWallLimitMs(BigDecimal hostFactor) {
        return 2 * effectiveCpuLimitMs(hostFactor);
    }

    /** Bắt đầu dựng một job. Dùng builder vì record này có năm số nguyên đứng liền nhau. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder cho {@link JudgeJobDto}.
     *
     * <p>Lý do tồn tại rất cụ thể: {@code compileTimeLimitMs, compileMemoryKb, timeLimitMs,
     * memoryLimitKb, outputLimitKb} là năm {@code int} liền nhau. Gọi constructor theo vị trí
     * mà đảo nhầm hai cái thì trình biên dịch im lặng, test đơn vị vẫn xanh, và hệ thống
     * chấm sai giới hạn cho tới khi có người thắc mắc vì sao bài nào cũng MLE.
     *
     * <p>Nếu hai bạn thấy nó thừa thì xoá — record vẫn dùng được bình thường.
     */
    public static final class Builder {
        private long submissionId;
        private int attempt;
        private String traceId;
        private String languageCode;
        private String compileCommand;
        private String runCommand;
        private int compileTimeLimitMs = 10_000;      // mặc định của languages.compile_time_limit_ms
        private int compileMemoryKb = 1_048_576;      // mặc định của languages.compile_memory_kb
        private int timeLimitMs;
        private int memoryLimitKb;
        private int outputLimitKb = 65_536;           // mặc định của problems.output_limit_kb
        private String sourceContent;
        private String sourceSha256;
        private CheckerType checkerType = CheckerType.TOKEN;
        private BigDecimal checkerEpsilon;
        private ScoringMode scoringMode = ScoringMode.ALL_OR_NOTHING;
        private int maxScore;
        private int testdataVersion;
        private String testdataManifestSha256;
        private List<TestcaseMetaDto> testcases = List.of();

        private Builder() {
        }

        public Builder submission(long submissionId, int attempt) {
            this.submissionId = submissionId;
            this.attempt = attempt;
            return this;
        }

        public Builder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        public Builder language(String languageCode, String compileCommand, String runCommand) {
            this.languageCode = languageCode;
            this.compileCommand = compileCommand;
            this.runCommand = runCommand;
            return this;
        }

        public Builder compileLimits(int compileTimeLimitMs, int compileMemoryKb) {
            this.compileTimeLimitMs = compileTimeLimitMs;
            this.compileMemoryKb = compileMemoryKb;
            return this;
        }

        /** Giới hạn đã quy về máy chấm chuẩn — worker sẽ nhân tiếp {@code host_factor}. */
        public Builder runLimitsOnReferenceHost(int timeLimitMs, int memoryLimitKb, int outputLimitKb) {
            this.timeLimitMs = timeLimitMs;
            this.memoryLimitKb = memoryLimitKb;
            this.outputLimitKb = outputLimitKb;
            return this;
        }

        public Builder source(String sourceContent, String sourceSha256) {
            this.sourceContent = sourceContent;
            this.sourceSha256 = sourceSha256;
            return this;
        }

        public Builder checker(CheckerType checkerType, BigDecimal checkerEpsilon) {
            this.checkerType = checkerType;
            this.checkerEpsilon = checkerEpsilon;
            return this;
        }

        /** @param maxScore điểm tối đa — do API tính, xem javadoc của record */
        public Builder scoring(ScoringMode scoringMode, int maxScore) {
            this.scoringMode = scoringMode;
            this.maxScore = maxScore;
            return this;
        }

        public Builder testdata(int version, String manifestSha256, List<TestcaseMetaDto> testcases) {
            this.testdataVersion = version;
            this.testdataManifestSha256 = manifestSha256;
            this.testcases = testcases;
            return this;
        }

        public JudgeJobDto build() {
            return new JudgeJobDto(submissionId, attempt, traceId, languageCode,
                    compileCommand, runCommand, compileTimeLimitMs, compileMemoryKb,
                    timeLimitMs, memoryLimitKb, outputLimitKb, sourceContent, sourceSha256,
                    checkerType, checkerEpsilon, scoringMode, maxScore, testdataVersion,
                    testdataManifestSha256, testcases);
        }
    }

    /**
     * <b>Không bao giờ log record này.</b> Nó chứa mã nguồn của người dùng (bất biến #9).
     * {@code toString()} bị ghi đè để một dòng {@code log.info("job={}", job)} vô ý cũng
     * không làm rò rỉ gì.
     */
    @Override
    public String toString() {
        return "JudgeJobDto[submissionId=" + submissionId + ", attempt=" + attempt
                + ", language=" + languageCode + ", tests=" + testcases.size()
                + ", testdataVersion=" + testdataVersion + ", traceId=" + traceId + "]";
    }
}
