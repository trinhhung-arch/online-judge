package dev.oj.judging.application.usecase;

import dev.oj.judging.application.port.SubmissionEventBus;
import dev.oj.judging.application.port.SubmissionEventBus.SubmissionEvent;
import dev.oj.judging.application.port.SubmissionEventBus.SubmissionEventListener;
import dev.oj.judging.domain.SubmissionStatus;
import dev.oj.platform.security.Role;
import dev.oj.problems.domain.FeedbackLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WatchSubmissionUseCaseTest {

    private final JudgingFakes fakes = new JudgingFakes();
    private final List<SubmissionEvent> daGui = new ArrayList<>();
    private SubmissionEventListener kenh;

    @Test
    @DisplayName("★ TD-06 · DONE tới giữa lúc đăng ký kênh và lúc đọc trạng thái: luồng mở ra vẫn thấy DONE, không lỡ")
    void done_toi_giua_dang_ky_va_doc() {
        fakes.submissions.found = JudgingFakes.submission(101L, SubmissionStatus.QUEUED, 0);
        SubmissionEventBus bus = new BusGia() {
            @Override
            public AutoCloseable subscribe(long submissionId, SubmissionEventListener listener) {
                fakes.submissions.found = JudgingFakes.submission(101L, SubmissionStatus.DONE, 1);
                return () -> {
                };
            }
        };

        try (var watch = useCase(bus).start(101L, daGui::add)) {
            assertThat(watch.current().status())
                    .as("đọc trạng thái SAU khi đăng ký — đổi thứ tự là trang đứng ở QUEUED tới khi luồng hết hạn")
                    .isEqualTo("DONE");
            assertThat(watch.alreadyFinished()).isFalse();
        }
    }

    @Test
    @DisplayName("★ TD-05 · đề NONE: luồng chỉ nói 'đang chấm' MỘT lần, không kèm số test — không đoán được test sai")
    void de_none_khong_lo_so_test() {
        fakes.submissions.feedbackLevel = FeedbackLevel.NONE;
        moLuong(1);

        kenh.onEvent(SubmissionEvent.progress(101L, 2, 20, 50));
        kenh.onEvent(SubmissionEvent.progress(101L, 2, 23, 50));
        kenh.onEvent(SubmissionEvent.done(101L, 2, "WA"));

        assertThat(daGui).extracting(SubmissionEvent::status).containsExactly("JUDGING", "DONE");
        assertThat(daGui).allSatisfy(e -> {
            assertThat(e.testsDone()).isNull();
            assertThat(e.totalTests()).isNull();
        });
    }

    @Test
    @DisplayName("TD-05 · đề TEST_INDEX: tiến độ đi nguyên vẹn, đúng như trước")
    void de_test_index_giu_tien_do() {
        moLuong(1);

        kenh.onEvent(SubmissionEvent.progress(101L, 2, 20, 50));
        kenh.onEvent(SubmissionEvent.progress(101L, 2, 23, 50));

        assertThat(daGui).extracting(SubmissionEvent::testsDone).containsExactly(20, 23);
    }

    @Test
    @DisplayName("★ TD-08 · tiến độ của lượt đã bị thu hồi không làm thanh tiến độ của lượt mới nhảy lùi")
    void bo_tien_do_cua_luot_cu() {
        moLuong(1);

        kenh.onEvent(SubmissionEvent.progress(101L, 2, 20, 50));
        kenh.onEvent(SubmissionEvent.progress(101L, 1, 40, 50));
        kenh.onEvent(SubmissionEvent.progress(101L, 2, 40, 50));
        kenh.onEvent(SubmissionEvent.done(101L, 2, "AC"));

        assertThat(daGui).extracting(SubmissionEvent::attempt).containsExactly(2, 2, 2);
        assertThat(daGui).extracting(SubmissionEvent::testsDone).containsExactly(20, 40, null);
    }

    private void moLuong(int attemptHienTai) {
        fakes.submissions.found = JudgingFakes.submission(101L, SubmissionStatus.JUDGING,
                attemptHienTai);
        useCase(new BusGia()).start(101L, daGui::add);
    }

    private WatchSubmissionUseCase useCase(SubmissionEventBus bus) {
        var get = new GetSubmissionUseCase(JudgingFakes.userIs(7L, Role.USER), fakes.submissions);
        return new WatchSubmissionUseCase(get, bus);
    }

    private class BusGia implements SubmissionEventBus {

        @Override
        public void publish(SubmissionEvent event) {
        }

        @Override
        public AutoCloseable subscribe(long submissionId, SubmissionEventListener listener) {
            kenh = listener;
            return () -> {
            };
        }
    }
}
