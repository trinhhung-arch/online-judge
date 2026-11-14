package dev.oj.judging.application.usecase;

import dev.oj.contract.JudgeProgressDto;
import dev.oj.contract.Verdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecordJudgeProgressUseCaseTest {

    private JudgingFakes fakes;
    private JudgingFakes.FakeEventBus events;
    private RecordJudgeProgressUseCase useCase;

    @BeforeEach
    void setUp() {
        fakes = new JudgingFakes();
        events = new JudgingFakes.FakeEventBus();
        useCase = new RecordJudgeProgressUseCase(fakes.queue, events, JudgingFakes.properties());
    }

    @Test
    @DisplayName("★ ADR 026 · lượt còn giữ lease: gia hạn đúng một lease rồi mới đẩy sự kiện")
    void con_giu_lease_thi_gia_han_va_day_su_kien() {
        useCase.record(tienDo(40));

        assertThat(fakes.calls).containsExactly("queue.renewLease");
        assertThat(fakes.queue.leaseSecondsSeen)
                .isEqualTo(JudgingFakes.properties().judge().leaseSeconds());
        assertThat(events.published).singleElement().satisfies(e -> {
            assertThat(e.submissionId()).isEqualTo(101L);
            assertThat(e.attempt()).isEqualTo(1);
            assertThat(e.status()).isEqualTo("JUDGING");
            assertThat(e.testsDone()).isEqualTo(40);
            assertThat(e.totalTests()).isEqualTo(100);
        });
    }

    @Test
    @DisplayName("★ B11 · lượt đã mất lease: bỏ qua im lặng, không sự kiện nào tới trình duyệt")
    void mat_lease_thi_khong_day_gi() {
        fakes.queue.leaseHeld = false;

        useCase.record(tienDo(40));

        assertThat(fakes.calls).containsExactly("queue.renewLease");
        assertThat(events.published).isEmpty();
    }

    private static JudgeProgressDto tienDo(int toOrdinal) {
        return new JudgeProgressDto(101L, 1, toOrdinal, toOrdinal, 100,
                List.of(new JudgeProgressDto.TestOutcome(toOrdinal, Verdict.AC, 12, 2048)));
    }
}
