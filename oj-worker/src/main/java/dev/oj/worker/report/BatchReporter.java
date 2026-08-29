package dev.oj.worker.report;

import dev.oj.contract.JudgeJobDto;
import dev.oj.contract.JudgeProgressDto;
import dev.oj.contract.JudgeProgressDto.TestOutcome;
import dev.oj.contract.Verdict;
import dev.oj.worker.client.JudgeApiClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * ★ Bước 3.7 — gom kết quả từng test thành lô {@value JudgeProgressDto#BATCH_SIZE} rồi mới gửi.
 *
 * <h2>Vì sao không gửi từng test một</h2>
 * Một bài 1000 test thành 1000 request HTTP. Đó là lỗi DMOJ đã mắc rồi phải thêm rate limit
 * để tự cứu mình khỏi chính worker của mình. Lô 20 cắt hai mươi lần số round-trip, và mắt
 * người vẫn thấy thanh tiến độ chạy mượt — 20/1000 test là 2%, không ai phân biệt được với
 * chuyển động liên tục.
 *
 * <h2>Vì sao là một phiên có {@code close()}, không phải một hàng đợi nền</h2>
 * Lô cuối gần như không bao giờ đủ 20 (bài 47 test cho hai lô đủ và một lô 7). Một hàng đợi
 * nền sẽ hoặc bỏ quên phần dư, hoặc cần một bộ đếm giờ để xả — thêm một luồng và một nguồn
 * bất định cho việc vẽ thanh phần trăm. {@link Session} là {@code AutoCloseable}, nên
 * {@code try-with-resources} lo phần dư, và trình biên dịch nhớ hộ.
 *
 * <p>Early exit ({@code oj-worker/CLAUDE.md} mục 4) làm lô dở dang thành chuyện thường xuyên
 * chứ không phải ngoại lệ: bài sai ở test 3 thì lô đầu chỉ có 3 phần tử.
 */
@Component
public class BatchReporter {

    private final JudgeApiClient api;

    public BatchReporter(JudgeApiClient api) {
        this.api = api;
    }

    public Session open(JudgeJobDto job) {
        return new Session(job);
    }

    /** Một phiên gom lô cho đúng một lượt chấm. Không dùng chung giữa các luồng. */
    public final class Session implements AutoCloseable {

        private final JudgeJobDto job;
        private final List<TestOutcome> pending = new ArrayList<>(JudgeProgressDto.BATCH_SIZE);
        private int firstOrdinal;

        private Session(JudgeJobDto job) {
            this.job = job;
        }

        public void add(int ordinal, Verdict verdict, long cpuTimeMs, long memoryKb) {
            if (pending.isEmpty()) {
                firstOrdinal = ordinal;
            }
            pending.add(new TestOutcome(ordinal, verdict, (int) cpuTimeMs, (int) memoryKb));
            if (pending.size() >= JudgeProgressDto.BATCH_SIZE) {
                flush();
            }
        }

        /** Gửi nốt phần dư. Gọi kể cả khi lượt chấm hỏng — tiến độ đã đi được vẫn đúng. */
        @Override
        public void close() {
            flush();
        }

        private void flush() {
            if (pending.isEmpty()) {
                return;
            }
            int lastOrdinal = pending.get(pending.size() - 1).ordinal();
            // reportProgress KHÔNG ném (xem javadoc của nó): một API chập chờn không được
            // phép làm hỏng lượt chấm đang chạy dở.
            api.reportProgress(new JudgeProgressDto(
                    job.submissionId(), job.attempt(),
                    firstOrdinal, lastOrdinal, job.testcases().size(),
                    List.copyOf(pending)));
            pending.clear();
        }
    }
}
