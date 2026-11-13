package dev.oj.judging.application.usecase;

import dev.oj.judging.application.port.SubmissionEventBus;
import dev.oj.judging.domain.SubmissionStatus;
import dev.oj.platform.security.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WatchSubmissionUseCaseTest {

    @Test
    @DisplayName("★ TD-06 · DONE tới giữa lúc đăng ký kênh và lúc đọc trạng thái: luồng mở ra vẫn thấy DONE, không lỡ")
    void done_toi_giua_dang_ky_va_doc() {
        var fakes = new JudgingFakes();
        fakes.submissions.found = JudgingFakes.submission(101L, SubmissionStatus.QUEUED, 0);
        var get = new GetSubmissionUseCase(JudgingFakes.userIs(7L, Role.USER), fakes.submissions);
        SubmissionEventBus bus = new SubmissionEventBus() {
            @Override
            public void publish(SubmissionEvent event) {
            }

            @Override
            public AutoCloseable subscribe(long submissionId, SubmissionEventListener listener) {
                fakes.submissions.found = JudgingFakes.submission(101L, SubmissionStatus.DONE, 1);
                return () -> {
                };
            }
        };

        try (var watch = new WatchSubmissionUseCase(get, bus).start(101L, event -> {
        })) {
            assertThat(watch.current().status())
                    .as("đọc trạng thái SAU khi đăng ký — đổi thứ tự là trang đứng ở QUEUED tới khi luồng hết hạn")
                    .isEqualTo("DONE");
            assertThat(watch.alreadyFinished()).isFalse();
        }
    }
}
