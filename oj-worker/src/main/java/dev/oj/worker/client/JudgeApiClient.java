package dev.oj.worker.client;

import dev.oj.contract.ClaimRequestDto;
import dev.oj.contract.HostBenchmarkDto;
import dev.oj.contract.JudgeEndpoints;
import dev.oj.contract.JudgeJobDto;
import dev.oj.contract.JudgeResultDto;
import dev.oj.worker.config.WorkerProperties;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Optional;

/**
 * <b>Nơi duy nhất trong {@code oj-worker} nói chuyện với {@code oj-api}</b>, và nó biết đúng
 * hai đường dẫn.
 *
 * <p>Đó không phải sự tối giản cho đẹp: worker không có {@code DataSource}, không có Redis,
 * không có MinIO (bất biến #3). Toàn bộ những gì nó biết về thế giới bên ngoài nằm trong file
 * này — nên rà soát bề mặt phụ thuộc của worker là đọc một file.
 *
 * <h2>Hai loại lỗi, hai cách xử lý ngược nhau</h2>
 * <ul>
 *   <li><b>4xx — lỗi của chính worker.</b> Payload không bao giờ hợp lệ (verdict lạ,
 *       {@code score > maxScore}, secret sai). Retry là lặp vô hạn một thứ hỏng. Ném
 *       {@link JudgeApiException} với {@code retryable = false}.</li>
 *   <li><b>5xx hoặc mất kết nối — lỗi phía kia.</b> API đang restart, DB chớp tắt, mạng đứt.
 *       Kết quả vẫn đúng, chỉ chưa gửi được → <b>giữ lại và thử lại</b>, đừng vứt đi
 *       ({@code oj-worker/CLAUDE.md} mục 6). Vứt một kết quả nghĩa là bài phải chờ hết lease
 *       120s rồi chấm lại từ đầu — phí một lượt chấm thật.</li>
 * </ul>
 */
@Component
public class JudgeApiClient {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(JudgeApiClient.class);


    private final RestClient http;

    public JudgeApiClient(WorkerProperties properties, RestClient.Builder builder) {
        Duration timeout = properties.requestTimeout();
        this.http = builder
                .baseUrl(properties.apiBaseUrl())
                // Secret gắn một lần ở đây. Không truyền nó qua tham số phương thức: mỗi chỗ
                // truyền là một chỗ nữa nó có thể lọt vào một dòng log.
                .defaultHeader(JudgeEndpoints.SECRET_HEADER, properties.internalSecret())
                .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory() {{
                    setConnectTimeout((int) timeout.toMillis());
                    setReadTimeout((int) timeout.toMillis());
                }})
                .build();
    }

    /**
     * Xin một việc. <b>{@code 204} nghĩa là hàng đợi rỗng</b>, không phải lỗi — vòng lặp ngủ
     * một nhịp rồi hỏi lại.
     */
    public Optional<JudgeJobDto> claim(ClaimRequestDto request) {
        try {
            JudgeJobDto job = http.post()
                    .uri(JudgeEndpoints.CLAIM)
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new JudgeApiException("claim trả " + res.getStatusCode(),
                                res.getStatusCode().is5xxServerError());
                    })
                    .body(JudgeJobDto.class);
            return Optional.ofNullable(job);   // 204 -> body rỗng -> null
        } catch (JudgeApiException e) {
            throw e;
        } catch (Exception e) {
            // Mất kết nối, timeout, DNS hỏng — API đang xuống, thử lại sau.
            throw new JudgeApiException("không gọi được " + JudgeEndpoints.CLAIM, true, e);
        }
    }

    /**
     * Trả kết quả. API luôn đáp {@code 204} cho cả ba kết cục (đã ghi · bị khoá lạc quan từ
     * chối · IE còn lượt) — worker <b>không được</b> coi 204 là "kết quả đã vào DB". Nó chỉ
     * có nghĩa "API đã nhận và tự quyết định", và đó là hành vi đúng.
     */
    public void reportResult(JudgeResultDto result) {
        try {
            http.post()
                    .uri(JudgeEndpoints.RESULT)
                    .body(result)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new JudgeApiException("result trả " + res.getStatusCode(),
                                res.getStatusCode().is5xxServerError());
                    })
                    .toBodilessEntity();
        } catch (JudgeApiException e) {
            throw e;
        } catch (Exception e) {
            throw new JudgeApiException("không gọi được " + JudgeEndpoints.RESULT, true, e);
        }
    }

    /**
     * Báo một phép đo tốc độ máy chấm. Luôn {@code 204}.
     *
     * <p><b>Không đưa vào {@code ResultBuffer}.</b> Buffer tồn tại để không mất bài nộp; một
     * phép đo bị mất thì 15 phút nữa có phép đo khác. Giữ lại và retry là làm hàng đợi cứu
     * bài nộp phải chia chỗ với dữ liệu vận hành.
     *
     * <p>Vì thế phương thức này <b>không ném</b>: API xuống là chuyện của {@code JudgeLoop},
     * và một lỗi ở đây không được phép làm hỏng luồng lịch đo.
     */
    public void reportBenchmark(HostBenchmarkDto benchmark) {
        try {
            http.post()
                    .uri(JudgeEndpoints.BENCHMARK)
                    .body(benchmark)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Không gửi được phép đo tốc độ máy về {}: {}. Bỏ qua — 15 phút nữa đo lại.",
                    JudgeEndpoints.BENCHMARK, e.toString());
        }
    }

    /** Lỗi khi gọi API. {@link #retryable()} quyết định {@code ResultBuffer} giữ hay bỏ. */
    public static class JudgeApiException extends RuntimeException {

        private final boolean retryable;

        public JudgeApiException(String message, boolean retryable) {
            this(message, retryable, null);
        }

        public JudgeApiException(String message, boolean retryable, Throwable cause) {
            super(message, cause);
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }
    }
}
