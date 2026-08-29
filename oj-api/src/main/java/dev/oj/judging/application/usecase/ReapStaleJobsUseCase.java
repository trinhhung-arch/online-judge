package dev.oj.judging.application.usecase;

import dev.oj.judging.application.port.JudgeQueueRepository;
import dev.oj.judging.application.port.SubmissionRepository;
import dev.oj.platform.config.JudgeTransactional;
import dev.oj.platform.security.InternalAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Thu hồi những bài kẹt ở {@code JUDGING} quá hạn lease — FR-SUB-03.
 *
 * <h2>Task có tỉ lệ giá trị/công sức cao nhất toàn dự án</h2>
 * Ba chục dòng này là mạng an toàn cho <b>năm loại sự cố khác nhau</b>
 * ({@code nfrplan.md} 5.1):
 *
 * <pre>
 *   worker chết giữa chừng          lease hết hạn -> bài về QUEUED -> worker khác chấm
 *   RabbitMQ chết                   không ai được báo, nhưng hàng vẫn nằm trong judge_queue
 *   publish thất bại sau COMMIT     y hệt trên — đây là lý do SubmitSolution dám nuốt lỗi publish
 *   mạng đứt giữa lúc chấm          kết quả không về được, lease hết hạn, chấm lại
 *   deploy worker giữa chừng        graceful shutdown lo phần lớn, reaper lo phần còn lại
 * </pre>
 *
 * Một cơ chế, năm sự cố. Và nó là thứ biến R1 ("0 bài mất, tuyệt đối") từ một lời hứa thành
 * một tính chất.
 *
 * <h2>Hai điều tuyệt đối không làm ở đây</h2>
 * <ul>
 *   <li><b>Không tăng {@code attempt}.</b> Lần claim kế tiếp mới tăng. Tăng ở cả hai chỗ thì
 *       {@code judge_runs} có lỗ hổng số thứ tự mà sau này không ai giải thích được.</li>
 *   <li><b>Không ghi verdict.</b> Reaper không biết gì về kết quả chấm; nó chỉ trả bài về
 *       hàng đợi. Một reaper biết ghi {@code IE} là một reaper có thể ghi đè verdict thật của
 *       một worker chỉ đang chậm.</li>
 * </ul>
 */
@InternalAccess("bộ lập lịch @Scheduled trong chính tiến trình này — lời gọi không đến từ mạng, nên không có tầng xác thực nào để đi qua.")
@Service
public class ReapStaleJobsUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReapStaleJobsUseCase.class);

    private final JudgeQueueRepository queue;
    private final SubmissionRepository submissions;

    public ReapStaleJobsUseCase(JudgeQueueRepository queue, SubmissionRepository submissions) {
        this.queue = queue;
        this.submissions = submissions;
    }

    /**
     * Gọi định kỳ bởi {@code StaleJobReaper} ({@code @Scheduled}, chu kỳ
     * {@code oj.judge.reaper-interval}) — bộ hẹn giờ nằm ở infrastructure, luật nghiệp vụ nằm ở đây.
     *
     * <p>Hai câu ghi trong <b>một</b> transaction: thả lease, rồi đưa ảnh chụp trạng thái về
     * {@code QUEUED}. Tách ra hai transaction là có một cửa sổ mà bài đã nằm chờ trong hàng đợi
     * nhưng trang chi tiết vẫn hiện "đang chấm" — và nếu tiến trình chết đúng giữa cửa sổ đó,
     * nó hiện như thế mãi mãi.
     *
     * @return số bài đã thu hồi. {@code > 0} là tín hiệu vận hành: hoặc có worker vừa chết,
     *         hoặc lease đang ngắn hơn thời gian chấm thật của những bài nặng nhất
     */
    @JudgeTransactional
    public int reap() {
        List<Long> reclaimed = queue.reapExpired();
        if (reclaimed.isEmpty()) {
            return 0;
        }
        submissions.markQueued(reclaimed);
        log.warn("Reaper thu hồi {} bài quá hạn lease, đã đưa về QUEUED: {}",
                reclaimed.size(), reclaimed);
        return reclaimed.size();
    }
}
