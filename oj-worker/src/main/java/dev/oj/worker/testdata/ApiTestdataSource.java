package dev.oj.worker.testdata;

import dev.oj.worker.client.JudgeApiClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@ConditionalOnProperty(name = "oj.worker.testdata-source", havingValue = "api",
        matchIfMissing = true)
@Component
public class ApiTestdataSource implements TestdataSource {

    private final JudgeApiClient api;

    public ApiTestdataSource(JudgeApiClient api) {
        this.api = api;
    }

    @Override
    public byte[] fetch(String sha256) {
        try {
            return api.fetchTestdata(sha256);
        } catch (RuntimeException e) {
            throw new TestdataUnavailableException(
                    "Không tải được testdata " + sha256.substring(0, 8) + "... từ API: "
                            + e.getMessage());
        }
    }
}
