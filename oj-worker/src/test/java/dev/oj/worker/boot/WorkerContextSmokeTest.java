package dev.oj.worker.boot;

import dev.oj.worker.OjWorkerApplication;
import dev.oj.worker.client.JudgeApiClient;
import dev.oj.worker.client.JudgeDoorbell;
import dev.oj.worker.config.GracefulShutdown;
import dev.oj.worker.pipeline.JudgeLoop;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerContextSmokeTest {

    private static ConfigurableApplicationContext khoiDong() {
        return new SpringApplicationBuilder(OjWorkerApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--oj.worker.rabbit.enabled=false",
                        "--oj.worker.sandbox.enabled=false",
                        "--oj.worker.slots=1",
                        "--oj.worker.api-base-url=http://127.0.0.1:1",
                        "--oj.worker.idle-poll=30s",
                        "--oj.worker.shutdown-grace=200ms",
                        "--oj.worker.internal-secret=" + "x".repeat(32));
    }

    @Test
    @DisplayName("★ context dựng được, và bốn bean của đường chấm đều có mặt")
    void context_dung_duoc() {
        try (ConfigurableApplicationContext ctx = khoiDong()) {
            assertThat(ctx.getBean(JudgeApiClient.class)).isNotNull();
            assertThat(ctx.getBean(JudgeLoop.class)).isNotNull();
            assertThat(ctx.getBean(JudgeDoorbell.class)).isNotNull();
            assertThat(ctx.getBean(GracefulShutdown.class)).isNotNull();
        }
    }

    @Test
    @DisplayName("★ GracefulShutdown dừng SAU JudgeLoop (phase nhỏ hơn)")
    void thu_tu_tat_may_dung() {
        try (ConfigurableApplicationContext ctx = khoiDong()) {
            GracefulShutdown tatMayEm = ctx.getBean(GracefulShutdown.class);
            JudgeLoop loop = ctx.getBean(JudgeLoop.class);

            assertThat(tatMayEm.getPhase())
                    .as("phase nhỏ hơn = dừng sau, vì Spring dừng theo phase giảm dần")
                    .isLessThan(loop.getPhase());
        }
    }
}
