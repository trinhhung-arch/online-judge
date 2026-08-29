package dev.oj.it;

import dev.oj.judging.application.usecase.SubmitSolutionUseCase;
import dev.oj.judging.domain.SubmissionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ <b>P2 — nộp bài trả lời trong ≤300ms</b>, và {@code accept != process}.
 *
 * <h2>Test này đo gì, và KHÔNG đo gì</h2>
 * Nó đo phần {@code SubmitSolutionUseCase}: validate → ba câu ghi → COMMIT → publish. Đó là
 * <b>ngân sách 50ms</b> trong tổng 300ms của P2 ({@code nfrplan.md} 2.1); phần còn lại là
 * overhead của framework và mạng.
 *
 * <p>Nó <b>không</b> đo p95 HTTP thật dưới tải — việc đó cần load test k6 với 500 người nộp
 * đồng thời ở tuần 12. Ngưỡng khẳng định dưới đây vì thế đặt ở cả 300ms: đủ chặt để bắt một
 * hồi quy nghiêm trọng (ai đó thêm một lời gọi I/O vào đường nóng), đủ lỏng để không đỏ vì
 * JIT chưa nóng hay container đang bận.
 *
 * <p>Con số thật được in ra log — đó mới là thứ đáng nhìn, và là baseline để so ở tuần 12.
 */
class SubmitLatencyIT extends PostgresIT {

    private static final Logger log = LoggerFactory.getLogger(SubmitLatencyIT.class);

    private static final int WARMUP = 20;
    private static final int SAMPLES = 100;
    private static final Duration P2_BUDGET = Duration.ofMillis(300);

    @Autowired SubmitSolutionUseCase submitSolution;

    @Test
    @DisplayName("★ p95 của đường nộp bài nằm trong ngân sách P2")
    void nop_bai_nhanh_hon_ngan_sach_P2() {
        for (int i = 0; i < WARMUP; i++) {
            submit(i);
        }

        List<Long> nanos = new ArrayList<>(SAMPLES);
        for (int i = 0; i < SAMPLES; i++) {
            long start = System.nanoTime();
            submit(WARMUP + i);
            nanos.add(System.nanoTime() - start);
        }
        Collections.sort(nanos);

        Duration p50 = Duration.ofNanos(nanos.get(SAMPLES / 2));
        Duration p95 = Duration.ofNanos(nanos.get((int) (SAMPLES * 0.95)));
        Duration max = Duration.ofNanos(nanos.get(SAMPLES - 1));
        log.info("P2 (phần use-case, ngân sách 50ms trong tổng 300ms): "
                        + "p50={}ms p95={}ms max={}ms trên {} mẫu",
                p50.toMillis(), p95.toMillis(), max.toMillis(), SAMPLES);

        assertThat(p95).as("p95 đường nộp bài vượt ngân sách P2 — có ai vừa thêm một lời gọi "
                + "I/O vào SubmitSolutionUseCase?").isLessThan(P2_BUDGET);
    }

    /**
     * ★ {@code accept != process} — quyết định quan trọng nhất của M1.
     *
     * <p>Sau khi {@code submit} trả về: bài ở {@code QUEUED}, <b>chưa có verdict</b>, và
     * <b>chưa có bản ghi chấm nào</b>. Nếu một ngày nào đó test này đỏ, nghĩa là có người vừa
     * làm cho đường nộp bài chờ worker — và lúc đó worker chậm là cả site đơ.
     */
    @Test
    void nop_bai_KHONG_cho_verdict() {
        var accepted = submit(1_000);

        assertThat(accepted.status()).isEqualTo(SubmissionStatus.QUEUED);
        assertThat(verdict(accepted.submissionId())).isNull();
        assertThat(countJudgeRuns()).isZero();
    }

    /**
     * ★ Xoá khoá rate limit <b>ngoài</b> vùng đo, trước mỗi mẫu.
     *
     * <p>FR-SUB-08 cho phép 1 bài / 10 giây — một quy tắc viết cho con người. Đo p95 thì cần
     * 100 mẫu liên tiếp, nhanh hơn mọi thứ một con người làm được. Hai yêu cầu này không sống
     * chung, và cách giải quyết đúng là nói ra chứ không phải bỏ một trong hai.
     *
     * <p>Quan trọng: chốt rate limit <b>vẫn chạy</b> với cửa sổ 10 giây thật, và nó vẫn nằm
     * trong con số p95 đo được. Nếu ai đó thêm một lời gọi I/O chậm vào chốt ấy, test này vẫn
     * bắt được — thứ mà việc tắt hẳn giới hạn sẽ che mất.
     *
     * <p>Lệnh xoá nằm ngoài {@code System.nanoTime()} nên nó không tính vào phép đo.
     * Con số 10 giây thật được kiểm ở {@code SubmissionRateLimitIT}.
     */
    private SubmitSolutionUseCase.SubmissionAccepted submit(int seed) {
        quenLuotNopVuaRoi(USER_ID);
        return submitSolution.submit(new SubmitSolutionUseCase.Command(
                PROBLEM_ID, "cpp20", "// EXPECT: AC\nint main(){ return " + seed + "; }"));
    }

    private String verdict(long id) {
        return jdbc.sql("SELECT verdict FROM submissions WHERE id = :id")
                .param("id", id).query(String.class).optional().orElse(null);
    }

    private int countJudgeRuns() {
        return jdbc.sql("SELECT count(*)::int FROM judge_runs").query(Integer.class).single();
    }
}
