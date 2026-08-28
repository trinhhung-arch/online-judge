package dev.oj.judging.domain;

import dev.oj.contract.JudgeResultDto;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Bản ghi <b>bất biến</b> của một attempt — bảng {@code judge_runs} ở V3.
 *
 * <p>Một hàng mỗi lần chấm, không bao giờ UPDATE, không bao giờ DELETE. Hàng rào không phải
 * là lời hứa mà là quyền: {@code REVOKE UPDATE, DELETE ON judge_runs FROM oj_app} (V9) —
 * trigger thì tắt được, quyền thì không.
 *
 * <p><b>Vì sao giữ cả lịch sử:</b> rejudge tạo attempt mới thay vì ghi đè (FR-ADM-01). Ngày
 * một đề sửa testdata rồi bài AC hôm qua thành WA hôm nay, đây là chỗ duy nhất trả lời được
 * <i>vì sao</i> — cùng với {@code testdataVersion} và {@code hostFactor} của chính lần chấm đó.
 * Khoá chính {@code (submissionId, attempt)} đồng thời là lớp chống trùng thứ hai bên cạnh
 * khoá lạc quan trên {@link JudgeQueueEntry}.
 *
 * @param attempt          từ {@link DomainRules#FIRST_ATTEMPT} trở lên — không có bản ghi nào
 *                         mang {@code attempt = 0}, vì số đó tăng lúc claim
 * @param hostName         <b>tên</b> máy chấm, không phải {@code judge_hosts.id}. Worker gửi
 *                         tên và không biết id trong DB tồn tại (bất biến #3, javadoc của
 *                         {@code JudgeResultDto.hostName}); việc phân giải tên sang id là một
 *                         sub-select trong câu {@code INSERT} ở infrastructure. Domain không
 *                         mang một khoá ngoại mà chính nó không tra được
 * @param hostFactor       hệ số hiệu chuẩn của máy vừa chấm. Ghi lại vì con số thời gian chỉ
 *                         có nghĩa khi biết nó đo trên máy nào ({@code nfrplan.md} 9.1)
 * @param testdataVersion  phiên bản testdata đã dùng cho chính attempt này
 * @param testsRun         số test đã chạy thật; nhỏ hơn tổng số test khi early exit
 * @param compileLog       output từ mã của <b>chính người nộp</b> nên được phép lưu và được
 *                         phép cho tác giả xem (FR-SUB-06). Tự cắt, xem {@link #truncate}
 * @param isolateStatus    nguyên văn file {@code meta} của isolate khi có sự cố.
 *                         <b>Không bao giờ chứa stdout chương trình hay nội dung testcase</b>
 * @param traceId          nối API → queue → worker → kết quả thành một câu chuyện đọc được
 * @param startedAt        worker báo; có thể {@code null}
 * @param finishedAt       API tự đặt lúc ghi
 */
public record JudgeRun(
        long submissionId,
        int attempt,
        String hostName,
        BigDecimal hostFactor,
        int languageId,
        int testdataVersion,
        JudgeOutcome outcome,
        int testsRun,
        String compileLog,
        String isolateStatus,
        String traceId,
        Instant startedAt,
        Instant finishedAt) {

    private static final String TRUNCATION_MARK = "\n… [đã cắt bớt]";

    public JudgeRun {
        if (submissionId <= 0 || languageId <= 0) {
            throw new IllegalArgumentException("submissionId và languageId phải dương");
        }
        if (hostName == null || hostName.isBlank()) {
            throw new IllegalArgumentException("hostName không được rỗng — số đo thời gian "
                    + "không kèm tên máy là số đo vô nghĩa (nfrplan.md 9.1)");
        }
        if (attempt < DomainRules.FIRST_ATTEMPT) {
            throw new IllegalArgumentException(
                    "attempt phải >= " + DomainRules.FIRST_ATTEMPT + ", nhận được: " + attempt);
        }
        if (testdataVersion < 1) {
            throw new IllegalArgumentException("testdataVersion phải >= 1: " + testdataVersion);
        }
        if (hostFactor == null || hostFactor.signum() <= 0) {
            throw new IllegalArgumentException("hostFactor phải dương: " + hostFactor);
        }
        if (outcome == null) {
            throw new NullPointerException("outcome");
        }
        if (testsRun < 0) {
            throw new IllegalArgumentException("testsRun không âm: " + testsRun);
        }
        if (finishedAt == null) {
            throw new NullPointerException("finishedAt");
        }
        compileLog = truncate(compileLog, JudgeResultDto.MAX_COMPILE_LOG_BYTES);
        isolateStatus = truncate(isolateStatus, JudgeResultDto.MAX_ISOLATE_STATUS_BYTES);
    }

    /** Lần chấm đầu tiên — chưa từng bị reaper thu hồi và chưa từng bị rejudge. */
    public boolean isFirstAttempt() {
        return attempt == DomainRules.FIRST_ATTEMPT;
    }

    /**
     * <b>Cắt, không ném</b> — và đây là quyết định quan trọng nhất của file này.
     *
     * <p>Ném lỗi vì một log compiler 40KB nghĩa là: API từ chối kết quả → transaction rollback
     * → hàng vẫn nằm trong {@code judge_queue} → reaper thu hồi → worker chấm lại → sinh ra
     * đúng cái log 40KB đó → từ chối tiếp. Một vòng lặp vô hạn ăn hết năng lực chấm của cả
     * hệ thống vì một bài nộp có template C++ lồng nhau. Cùng nguyên tắc mà
     * {@code oj-contract} đã chọn: dữ liệu đi <i>vào</i> worker thì ném, dữ liệu đi <i>ra</i>
     * từ worker thì cắt.
     *
     * <p>Đếm theo <b>byte</b> vì ràng buộc trong DB là {@code octet_length(compile_log) <=
     * 32768}, và lùi về đầu ký tự UTF-8 để không cắt một ký tự làm đôi — một chuỗi hỏng nửa
     * ký tự sẽ làm vỡ phần render ở trình duyệt đúng lúc người dùng cần đọc lỗi biên dịch nhất.
     */
    private static String truncate(String value, int maxBytes) {
        if (value == null) {
            return null;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return value;
        }
        int cut = maxBytes - TRUNCATION_MARK.getBytes(StandardCharsets.UTF_8).length;
        while (cut > 0 && (bytes[cut] & 0xC0) == 0x80) {
            cut--;   // 10xxxxxx là byte nối — lùi tới byte mở đầu ký tự
        }
        return cut <= 0 ? "" : new String(bytes, 0, cut, StandardCharsets.UTF_8) + TRUNCATION_MARK;
    }

    // -------------------------------------------------------------------------
    // KHÔNG kiểm finishedAt >= startedAt, và đó là cố ý.
    //
    // startedAt do worker gửi, finishedAt do API đặt bằng đồng hồ của mình. Hai máy khác
    // nhau (host ARM + WSL của hai người) thì lệch đồng hồ vài trăm mili giây là bình thường.
    // Ném lỗi vì chuyện đó là chặn đúng đường ghi verdict — xem lại đoạn về vòng lặp ở trên.
    //
    // KHÔNG có factory from(JudgeResultDto ...) ở đây.
    // Ánh xạ domain <-> oj-contract nằm ở ContractMapper trong infrastructure (Bước M1-7).
    // Domain biết oj-contract như biết một từ điển chung; nó không biết có ai đang gửi HTTP.
    // -------------------------------------------------------------------------
}
