package dev.oj.worker.run;

import dev.oj.contract.JudgeJobDto;
import dev.oj.contract.JudgeResultDto;
import dev.oj.contract.Verdict;
import dev.oj.worker.config.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * ★ Hiện thực M1 của {@link JudgeRunner}. <b>KHÔNG biên dịch, KHÔNG thực thi, KHÔNG spawn một
 * tiến trình nào.</b>
 *
 * <p>Nó đọc <b>dòng đầu tiên</b> của source tìm một chỉ thị, rồi trả về verdict tương ứng:
 *
 * <pre>
 *   // EXPECT: AC      -> AC, điểm tối đa
 *   // EXPECT: WA      -> WA, fail ở test 1
 *   // EXPECT: TLE     -> ngủ quá hạn rồi trả TLE
 *   // EXPECT: CE      -> CE kèm một log compiler giả
 *   // EXPECT: CRASH   -> ném lỗi giữa chừng, để test reaper
 *   (không có chỉ thị) -> IE
 * </pre>
 *
 * <h2>Vì sao mặc định là {@code IE} chứ không phải {@code AC}</h2>
 * Vì đây là một bản giả, và ngày tệ nhất của nó là ngày nó chạy trên host thật mà không ai
 * nhận ra. Nếu mặc định là {@code AC} thì hôm đó <b>mọi bài nộp đều AC</b> — hệ thống trông
 * vẫn "chạy bình thường", mọi con số "đã giải bao nhiêu bài" thành vô nghĩa, và không có gì
 * báo động. Với {@code IE} thì hỏng ngay, hỏng ồn ào, và FR-SUB-12 sẽ hiện lỗi cho người dùng
 * trong ba lần nộp đầu tiên.
 *
 * <p>Cùng một nguyên tắc với {@code oj-worker/CLAUDE.md} mục 6: <i>không chắc chắn kết quả là
 * gì thì đó là {@code IE}</i>. Ở đây thì nó <b>không bao giờ</b> chắc chắn — nó có đọc bài đâu.
 *
 * <h2>M1 chứng minh được gì mà không chạy mã người lạ</h2>
 * {@code accept != process} · reaper · khoá lạc quan · hai worker không chấm trùng · P2 < 300ms.
 * Không thứ nào trong số đó cần một trình biên dịch.
 */
@Component
@ConditionalOnProperty(prefix = "oj.worker.sandbox", name = "enabled", havingValue = "false")
public class ScriptedJudgeRunner implements JudgeRunner {

    private static final Logger log = LoggerFactory.getLogger(ScriptedJudgeRunner.class);

    /** Chỉ thị phải nằm ở dòng đầu, và chỉ dòng đầu được đọc — xem {@link #directiveOf}. */
    private static final String MARKER = "EXPECT:";

    private static final List<String> DIRECTIVES =
            List.of("AC", "WA", "TLE", "CE", "CRASH");

    private final WorkerProperties properties;

    public ScriptedJudgeRunner(WorkerProperties properties) {
        this.properties = properties;
        log.warn("""

                ┌────────────────────────────────────────────────────────────────┐
                │  CHẤM BÀI ĐANG GIẢ LẬP — ScriptedJudgeRunner (chỉ dành cho M1) │
                │  Không mã người dùng nào được thực thi. Bài không mang chỉ thị │
                │  '// EXPECT: ...' sẽ nhận verdict IE.                          │
                │  Thay bằng IsolateJudgeRunner khi 14/14 test tấn công xanh.    │
                └────────────────────────────────────────────────────────────────┘
                """);
    }

    @Override
    public JudgeResultDto run(JudgeJobDto job) {
        Instant startedAt = Instant.now();
        String directive = directiveOf(job.sourceContent());
        // Log CHỈ chỉ thị, không bao giờ log source (bất biến #9).
        log.info("submission {} attempt {} — kịch bản '{}'",
                job.submissionId(), job.attempt(), directive);

        return switch (directive) {
            case "AC" -> done(job, Verdict.AC, job.maxScore(), null, startedAt);
            case "WA" -> done(job, Verdict.WA, 0, 1, startedAt);
            case "TLE" -> timeLimitExceeded(job, startedAt);
            case "CE" -> JudgeResultDto.compileError(job.submissionId(), job.attempt(),
                    job.maxScore(), properties.hostName(), properties.hostFactor(),
                    startedAt, "error: kịch bản CE của ScriptedJudgeRunner, không phải "
                            + "log compiler thật");
            case "CRASH" -> throw new IllegalStateException(
                    "kịch bản CRASH — worker chết giữa chừng để test reaper (submission "
                            + job.submissionId() + ")");
            default -> JudgeResultDto.internalError(job.submissionId(), job.attempt(),
                    properties.hostName(), properties.hostFactor(), startedAt,
                    "ScriptedJudgeRunner: bài nộp không mang chỉ thị '// " + MARKER + " ...'. "
                            + "M1 không thực thi mã người dùng (bất biến #4).");
        };
    }

    /**
     * Ngủ cho quá giới hạn rồi mới trả {@code TLE} — để P3 (verdict end-to-end) và đường SSE
     * còn có một trường hợp "chấm lâu" thật để đo, thay vì mọi bài đều xong trong 1ms.
     *
     * <p>Ngủ thêm 10% chứ không phải gấp đôi: {@code Thread.sleep} ở đây giữ một slot, và giữ
     * lâu là làm méo chính con số throughput mà M1 cần đo.
     */
    private JudgeResultDto timeLimitExceeded(JudgeJobDto job, Instant startedAt) {
        long limitMs = job.timeLimitMs();
        try {
            Thread.sleep(limitMs / 10 + 1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return JudgeResultDto.internalError(job.submissionId(), job.attempt(),
                    properties.hostName(), properties.hostFactor(), startedAt,
                    "bị ngắt giữa kịch bản TLE");
        }
        return new JudgeResultDto(job.submissionId(), job.attempt(), Verdict.TLE,
                0, job.maxScore(), 1, 1,
                (int) limitMs + 30, 8192, null, null,
                properties.hostName(), properties.hostFactor(), startedAt, List.of());
    }

    private JudgeResultDto done(JudgeJobDto job, Verdict verdict, int score,
                                Integer failedTestOrdinal, Instant startedAt) {
        return new JudgeResultDto(job.submissionId(), job.attempt(), verdict,
                score, job.maxScore(), failedTestOrdinal,
                job.testcases().size(), 23, 8192, null, null,
                properties.hostName(), properties.hostFactor(), startedAt, List.of());
    }

    /**
     * Đọc <b>dòng đầu tiên và chỉ dòng đầu tiên</b>.
     *
     * <p>Quét cả file thì một bài nộp thật có chuỗi {@code EXPECT: AC} trong comment ở giữa sẽ
     * điều khiển được kết quả chấm của chính nó. Ở M1 thì vô hại vì chưa chấm thật, nhưng thói
     * quen "tin vào nội dung do người dùng gửi" là thứ không nên tập.
     *
     * @return một trong {@link #DIRECTIVES}, hoặc {@code "NONE"}
     */
    static String directiveOf(String source) {
        if (source == null || source.isBlank()) {
            return "NONE";
        }
        int end = source.indexOf('\n');
        String firstLine = (end < 0 ? source : source.substring(0, end))
                .toUpperCase(Locale.ROOT);
        int at = firstLine.indexOf(MARKER);
        if (at < 0) {
            return "NONE";
        }
        String tail = firstLine.substring(at + MARKER.length()).trim();
        return DIRECTIVES.stream().filter(tail::startsWith).findFirst().orElse("NONE");
    }
}
