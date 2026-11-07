package dev.oj.worker.client;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class JudgeApiClientConfig {

    @Bean
    public RestClient.Builder judgeRestClientBuilder() {
        return RestClient.builder();
    }
}
