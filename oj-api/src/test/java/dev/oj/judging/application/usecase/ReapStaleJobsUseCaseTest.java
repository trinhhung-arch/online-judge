package dev.oj.judging.application.usecase;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reaper — mạng an toàn cho năm loại sự cố, và là task có tỉ lệ giá trị/công sức cao nhất
 * toàn dự án ({@code nfrplan.md} 5.1).
 */
class ReapStaleJobsUseCaseTest {

    private JudgingFakes fakes;
    private ReapStaleJobsUseCase useCase;

    @BeforeEach
    void setUp() {
        fakes = new JudgingFakes();
        useCase = new ReapStaleJobsUseCase(fakes.queue, fakes.submissions);
    }

    /** ★ Lease hết hạn → về QUEUED. Và {@code attempt} <b>không</b> tăng ở đây. */
    @Test
    void lease_het_han_thi_ve_QUEUED_va_attempt_khong_tang() {
        fakes.queue.expired = List.of(101L, 102L);

        int reaped = useCase.reap();

        assertThat(reaped).isEqualTo(2);
        assertThat(fakes.submissions.requeued).containsExactly(101L, 102L);
        // Không có lời gọi nào đụng tới attempt — lần claim kế tiếp mới tăng.
        assertThat(fakes.submissions.judgingAttempt).isNull();
        assertThat(fakes.calls).containsExactly("queue.reapExpired", "submissions.markQueued");
    }

    /** Reaper không biết gì về verdict. Một reaper biết ghi IE là một reaper ghi đè được kết quả thật. */
    @Test
    void reaper_khong_bao_gio_ghi_verdict() {
        fakes.queue.expired = List.of(101L);

        useCase.reap();

        assertThat(fakes.calls).noneMatch(c -> c.contains("markDone") || c.contains("judgeRuns"));
    }

    /** Chu kỳ 15 giây chạy suốt ngày — không có gì quá hạn thì không tốn một câu ghi nào. */
    @Test
    void khong_co_gi_qua_han_thi_khong_ghi_gi() {
        int reaped = useCase.reap();

        assertThat(reaped).isZero();
        assertThat(fakes.calls).containsExactly("queue.reapExpired");
    }
}
