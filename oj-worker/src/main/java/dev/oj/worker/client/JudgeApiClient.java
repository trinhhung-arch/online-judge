package dev.oj.worker.client;

import dev.oj.contract.ClaimRequestDto;
import dev.oj.contract.HostBenchmarkDto;
import dev.oj.contract.JudgeEndpoints;
import dev.oj.contract.JudgeJobDto;
import dev.oj.contract.JudgeProgressDto;
import dev.oj.contract.JudgeResultDto;
import dev.oj.worker.config.WorkerProperties;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Optional;

@Component
public class JudgeApiClient {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(JudgeApiClient.class);

    private final RestClient http;

    public JudgeApiClient(WorkerProperties properties, RestClient.Builder builder) {
        Duration timeout = properties.requestTimeout();
        this.http = builder
                .baseUrl(properties.apiBaseUrl())
                .defaultHeader(JudgeEndpoints.SECRET_HEADER, properties.internalSecret())
                .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory() {{
                    setConnectTimeout((int) timeout.toMillis());
                    setReadTimeout((int) timeout.toMillis());
                }})
                .build();
    }

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
            return Optional.ofNullable(job);
        } catch (JudgeApiException e) {
            throw e;
        } catch (Exception e) {
            throw new JudgeApiException("không gọi được " + JudgeEndpoints.CLAIM, true, e);
        }
    }

    public void reportProgress(JudgeProgressDto progress) {
        try {
            http.post()
                    .uri(JudgeEndpoints.PROGRESS)
                    .body(progress)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.debug("Bỏ qua một lô tiến độ của submission {}: {}",
                    progress.submissionId(), e.toString());
        }
    }

    public byte[] fetchTestdata(String sha256) {
        try {
            byte[] noiDung = http.get()
                    .uri(JudgeEndpoints.TESTDATA + "/" + sha256)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new JudgeApiException(
                                "testdata " + sha256.substring(0, 8) + "... trả "
                                        + res.getStatusCode(),
                                res.getStatusCode().is5xxServerError());
                    })
                    .body(byte[].class);
            if (noiDung == null) {
                throw new JudgeApiException(
                        "testdata " + sha256.substring(0, 8) + "... trả thân rỗng", true);
            }
            return noiDung;
        } catch (JudgeApiException e) {
            throw e;
        } catch (Exception e) {
            throw new JudgeApiException(
                    "không gọi được " + JudgeEndpoints.TESTDATA, true, e);
        }
    }

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
