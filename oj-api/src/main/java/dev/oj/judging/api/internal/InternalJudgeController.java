package dev.oj.judging.api.internal;

import dev.oj.contract.ClaimRequestDto;
import dev.oj.contract.HostBenchmarkDto;
import dev.oj.contract.JudgeEndpoints;
import dev.oj.contract.JudgeJobDto;
import dev.oj.contract.JudgeResultDto;
import dev.oj.judging.application.usecase.ClaimJudgeJobUseCase;
import dev.oj.judging.application.usecase.RecordHostBenchmarkUseCase;
import dev.oj.judging.application.usecase.RecordJudgeResultUseCase;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hai endpoint nội bộ của M1. <b>Không nằm dưới {@code /api/v1/}</b>, và Cloudflare Tunnel
 * chỉ publish {@code /api/v1/**} — kiểm tay cấu hình tunnel ở tuần 9.
 *
 * <p>Xác thực bằng {@code InternalSecretFilter} (shared secret từ env), không phải JWT. Filter
 * đó đăng ký theo {@code urlPatterns("/internal/*")}, nên package này là <b>toàn bộ bề mặt
 * cần bảo vệ</b> — đặt riêng ra để {@code grep -r internal} trả về đúng một chỗ.
 *
 * <h2>Đây là phần đã đóng băng của {@code oj-contract}</h2>
 * Đổi chữ ký hai hàm dưới đây là một PR chạm cả hai vùng và hai người duyệt
 * ({@code CLAUDE.md} mục 5.1). Nhận và trả thẳng kiểu của {@code oj-contract}, không bọc
 * thêm DTO nào — một lớp bọc ở đây là một chỗ nữa để hai phía lệch nhau.
 */
@RestController
@RequestMapping(JudgeEndpoints.BASE)
public class InternalJudgeController {

    private final ClaimJudgeJobUseCase claimJudgeJob;
    private final RecordJudgeResultUseCase recordJudgeResult;
    private final RecordHostBenchmarkUseCase recordHostBenchmark;

    public InternalJudgeController(ClaimJudgeJobUseCase claimJudgeJob,
                                   RecordJudgeResultUseCase recordJudgeResult,
                                   RecordHostBenchmarkUseCase recordHostBenchmark) {
        this.claimJudgeJob = claimJudgeJob;
        this.recordJudgeResult = recordJudgeResult;
        this.recordHostBenchmark = recordHostBenchmark;
    }

    /**
     * Worker xin việc. {@code 200} + job, hoặc <b>{@code 204} khi hàng đợi rỗng</b>.
     *
     * <p>204 chứ không phải 200 với thân rỗng: một wrapper kiểu {@code {hasJob: false}} tạo
     * thêm một trạng thái sai được ({@code hasJob=true} mà {@code job=null}), còn 204 thì
     * không có chỗ nào để sai.
     */
    @PostMapping("/claim")   // JudgeEndpoints.CLAIM
    public ResponseEntity<JudgeJobDto> claim(@RequestBody ClaimRequestDto request) {
        return claimJudgeJob.claim(request)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * Worker trả kết quả. <b>Luôn {@code 204}</b>, cho cả ba kết cục: đã ghi, bị khoá lạc quan
     * từ chối, hoặc {@code IE} còn lượt nên bài quay lại hàng đợi.
     *
     * <p>Worker <b>không được</b> coi 204 là "kết quả đã vào DB" — nó chỉ có nghĩa "API đã
     * nhận và tự quyết định". Đó là hành vi đúng, không phải thiếu sót: nếu API trả về khác
     * nhau cho ba kết cục, worker sẽ có ba nhánh xử lý cho một việc mà nó không cần biết.
     *
     * <p>Một payload sai (verdict không hợp lệ, score > maxScore) bị compact constructor của
     * {@code JudgeResultDto} chặn ngay lúc Jackson dựng record, và {@code GlobalExceptionHandler}
     * đổi nó thành <b>400</b>. 400 chứ không phải 500 là có chủ ý: worker nhìn 5xx sẽ retry
     * mãi một payload không bao giờ hợp lệ, còn 4xx thì nó biết lỗi là của chính nó.
     */
    @PostMapping("/result")  // JudgeEndpoints.RESULT
    public ResponseEntity<Void> result(@RequestBody JudgeResultDto result) {
        recordJudgeResult.record(result);
        return ResponseEntity.noContent().build();
    }

    /**
     * Worker báo một phép đo tốc độ máy chấm. Luôn {@code 204}.
     *
     * <p><b>Endpoint duy nhất ở đây không nằm trên đường {@code nộp bài → verdict}</b>: worker
     * gọi 15 phút một lần từ luồng lịch riêng. Nó vẫn ở dưới {@code /internal/judge} vì nó
     * dùng cùng một shared secret và cùng một luật "không lộ ra tunnel".
     *
     * <p>{@code 204} kể cả khi máy chấm chưa có trong {@code judge_hosts}: một máy chưa đăng
     * ký vẫn chấm bài được ({@code judge_runs.host_id} cho phép NULL, S2), nên nó cũng không
     * đáng bị một mã lỗi. Use-case log lại; worker không có gì để làm khác đi.
     */
    @PostMapping("/benchmark")   // JudgeEndpoints.BENCHMARK
    public ResponseEntity<Void> benchmark(@RequestBody HostBenchmarkDto benchmark) {
        recordHostBenchmark.record(benchmark);
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------------------
    // M3 thêm endpoint thứ ba: POST /internal/judge/progress (JudgeProgressDto, lô 20 test).
    // Nó đẩy tiến độ lên Redis pub/sub -> SSE. LỌC theo feedback_level PHẢI xảy ra TRƯỚC khi
    // publish, không phải ở trình duyệt: payload đó mang verdict TỪNG test, và đẩy thẳng ra
    // là mở lại đúng đường rò rỉ mà mức NONE sinh ra để đóng (FeedbackLevel javadoc).
    // -------------------------------------------------------------------------
}
