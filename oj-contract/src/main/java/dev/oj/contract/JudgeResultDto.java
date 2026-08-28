package dev.oj.contract;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Kết quả cuối cùng của một lần chấm, gửi bằng {@code POST /internal/judge/result}.
 *
 * <p>API xử lý record này trong <b>một transaction</b>, và câu lệnh đầu tiên là khoá lạc quan
 * {@code DELETE FROM judge_queue WHERE submission_id=? AND attempt=?}. Trả 0 dòng nghĩa là
 * kết quả này thuộc về một {@code attempt} đã bị reaper thu hồi, hoặc là bản giao trùng của
 * RabbitMQ (at-least-once) — <b>bỏ qua im lặng, không ghi gì, không báo lỗi</b> (bất biến #7).
 *
 * <p>Vì vậy worker <b>không</b> được coi HTTP 204 là "kết quả đã được ghi". Nó chỉ có nghĩa
 * "API đã nhận và tự quyết định". Đó là hành vi đúng, không phải thiếu sót.
 *
 * <h2>Vì sao có trường bị cắt bớt thay vì bị từ chối</h2>
 * {@code compileLog} và {@code isolateStatus} được <b>cắt</b> trong constructor, không ném lỗi.
 * Nếu ném, API từ chối kết quả, reaper thu hồi bài, worker chấm lại, log dài y hệt, từ chối
 * tiếp — một vòng lặp vô hạn ăn hết năng lực chấm vì một bài có log compiler 40KB.
 *
 * @param submissionId       id bài nộp
 * @param attempt            đúng {@code attempt} nhận được ở {@code claim}. Sai một đơn vị là
 *                           kết quả bị bỏ qua — đây là cơ chế, không phải lỗi
 * @param verdict            một trong bảy
 * @param score              điểm đạt được
 * @param maxScore           điểm tối đa của đề tại {@code testdataVersion} này
 * @param failedTestOrdinal  <b>chỉ số thứ tự</b> test sai, hoặc {@code null}. Nội dung test
 *                           không bao giờ đi kèm — và API còn lọc tiếp theo
 *                           {@code problems.feedback_level} trước khi cho người dùng thấy
 *                           con số này (FR-PROB-07)
 * @param testsRun           số test đã chạy thật; nhỏ hơn tổng số test khi early exit
 * @param timeMs             CPU time lớn nhất qua các test, đã quy về máy chấm chuẩn.
 *                           <b>Đo CPU time, không phải wall time</b> — máy tải nặng thì wall
 *                           time làm cùng một bài lúc AC lúc TLE ({@code oj-worker/CLAUDE.md} mục 2)
 * @param memoryKb           bộ nhớ lớn nhất qua các test
 * @param compileLog         log compiler, tự cắt còn {@value #MAX_COMPILE_LOG_BYTES} byte.
 *                           Được phép lưu vì đây là output từ mã của chính người nộp.
 *                           <b>Không bao giờ chứa nội dung testcase hay stdout chương trình</b>
 * @param isolateStatus      nguyên văn file {@code meta} của isolate khi có sự cố. Khi
 *                           {@code isolate} trả mã lạ thì verdict là {@code IE} và trường này
 *                           giữ nguyên văn — không map bừa sang {@code RE}
 * @param hostName           tên máy chấm. Worker gửi <b>tên</b>, API tra ra
 *                           {@code judge_hosts.id} — worker không biết id trong DB (bất biến #3)
 * @param hostFactor         hệ số hiệu chuẩn của máy vừa chấm, ghi vào {@code judge_runs} để
 *                           sau này còn truy được vì sao một con số thời gian là như vậy
 * @param startedAt          lúc bắt đầu chấm; API tự đặt {@code finished_at = now()}
 * @param subtasks           điểm từng nhóm. <b>Rỗng ở M1</b> và ở mọi đề
 *                           {@code ALL_OR_NOTHING}; có dữ liệu từ M3 (V4, FR-PROB-06)
 */
public record JudgeResultDto(
        long submissionId,
        int attempt,
        Verdict verdict,
        int score,
        int maxScore,
        Integer failedTestOrdinal,
        int testsRun,
        Integer timeMs,
        Integer memoryKb,
        String compileLog,
        String isolateStatus,
        String hostName,
        BigDecimal hostFactor,
        Instant startedAt,
        List<SubtaskResultDto> subtasks) {

    /** Khớp {@code CHECK (octet_length(compile_log) <= 32768)} trên {@code judge_runs}. */
    public static final int MAX_COMPILE_LOG_BYTES = 32_768;

    /** Trần cho {@code isolateStatus} — không có CHECK trong DB, nhưng log vô hạn thì vô nghĩa. */
    public static final int MAX_ISOLATE_STATUS_BYTES = 4_096;

    public JudgeResultDto {
        ContractChecks.requirePositive(submissionId, "submissionId");
        ContractChecks.requireAtLeast(attempt, 1, "attempt");
        if (verdict == null) {
            throw new NullPointerException("verdict");
        }
        ContractChecks.requireAtLeast(score, 0, "score");
        ContractChecks.requireAtLeast(maxScore, 0, "maxScore");
        if (score > maxScore) {
            throw new IllegalArgumentException("score (" + score + ") > maxScore (" + maxScore + ")");
        }
        ContractChecks.requireNullOrRange(
                failedTestOrdinal, 1, TestcaseMetaDto.MAX_ORDINAL, "failedTestOrdinal");
        ContractChecks.requireAtLeast(testsRun, 0, "testsRun");
        ContractChecks.requireNullOrRange(timeMs, 0, Integer.MAX_VALUE, "timeMs");
        ContractChecks.requireNullOrRange(memoryKb, 0, Integer.MAX_VALUE, "memoryKb");
        ContractChecks.requireText(hostName, "hostName");
        if (hostFactor == null || hostFactor.signum() <= 0) {
            throw new IllegalArgumentException("hostFactor phải dương, nhận được: " + hostFactor);
        }
        if (startedAt == null) {
            throw new NullPointerException("startedAt");
        }

        // Cắt, không ném — xem javadoc của record.
        compileLog = ContractChecks.truncateUtf8(compileLog, MAX_COMPILE_LOG_BYTES);
        isolateStatus = ContractChecks.truncateUtf8(isolateStatus, MAX_ISOLATE_STATUS_BYTES);
        subtasks = ContractChecks.frozen(subtasks);

        // AC mà vẫn chỉ ra một test sai là mâu thuẫn — thường là bug gộp kết quả ở worker.
        if (verdict == Verdict.AC && failedTestOrdinal != null) {
            throw new IllegalArgumentException(
                    "verdict=AC nhưng failedTestOrdinal=" + failedTestOrdinal);
        }
    }

    /**
     * Kết quả cho một bài lỗi hệ thống. API sẽ tự cho chấm lại tối đa 2 lần trước khi
     * hiện {@code IE} cho người dùng (FR-SUB-12).
     *
     * <p>Dùng khi worker <b>không chắc chắn</b> kết quả là gì: không tải được testdata,
     * {@code isolate} trả mã lạ, box không dọn được. Không bao giờ đoán một verdict —
     * đoán sai trong contest thì không ai phát hiện ra, và đó mới là điều tệ.
     */
    public static JudgeResultDto internalError(long submissionId, int attempt, String hostName,
                                               BigDecimal hostFactor, Instant startedAt,
                                               String isolateStatus) {
        return new JudgeResultDto(submissionId, attempt, Verdict.IE, 0, 0, null, 0,
                null, null, null, isolateStatus, hostName, hostFactor, startedAt, List.of());
    }

    /** Bài không biên dịch được: không test nào chạy, log compiler là toàn bộ thông tin. */
    public static JudgeResultDto compileError(long submissionId, int attempt, int maxScore,
                                              String hostName, BigDecimal hostFactor,
                                              Instant startedAt, String compileLog) {
        return new JudgeResultDto(submissionId, attempt, Verdict.CE, 0, maxScore, null, 0,
                null, null, compileLog, null, hostName, hostFactor, startedAt, List.of());
    }

    /**
     * <b>Không bao giờ log nguyên record này.</b> {@code compileLog} là output từ mã người
     * dùng và có thể chứa đường dẫn tuyệt đối trong box ({@code oj-worker/CLAUDE.md} mục 7).
     */
    @Override
    public String toString() {
        return "JudgeResultDto[submissionId=" + submissionId + ", attempt=" + attempt
                + ", verdict=" + verdict + ", score=" + score + "/" + maxScore
                + ", testsRun=" + testsRun + ", timeMs=" + timeMs + ", memoryKb=" + memoryKb
                + ", host=" + hostName + "]";
    }
}
