package dev.oj.judging.application.usecase;

import dev.oj.judging.application.port.JudgeQueueRepository;
import dev.oj.judging.application.port.JudgeQueueRepository.ExhaustedJob;
import dev.oj.judging.application.port.SubmissionRepository;
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.security.InternalAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
 * <h2>★ Nhưng không phải vô hạn lần — V16, ADR 019</h2>
 * Bản trước thả lại mãi. Một bài làm sập worker, hay một đề mất testdata
 * ({@code ClaimJudgeJobUseCase.skipBrokenJob}), thì quay vòng mãi: treo trước mắt người nộp,
 * tốn một lease mỗi vòng, và chặn bảng xếp hạng kỳ thi phía sau nó. Giờ một bài được thả lại
 * tối đa {@code oj.judge.max-reaps} lần; lần hết lease kế tiếp, reaper <b>bỏ cuộc</b> và bài
 * nhận {@code IE} qua {@link RecordJudgeResultUseCase#boCuoc}.
 *
 * <h2>Điều tuyệt đối không làm ở đây</h2>
 * <ul>
 *   <li><b>Không tăng {@code attempt}.</b> Lần claim kế tiếp mới tăng. Tăng ở cả hai chỗ thì
 *       {@code judge_runs} có lỗ hổng số thứ tự mà sau này không ai giải thích được.</li>
 *   <li><b>Không tự ghi verdict.</b> Bỏ cuộc đi qua {@code RecordJudgeResultUseCase} — đúng
 *       khoá lạc quan mà mọi kết quả của worker đi qua. Một reaper tự ghi {@code IE} bằng câu
 *       lệnh của riêng nó là đường ghi verdict thứ hai, thứ ghi đè được kết quả thật của một
 *       worker chỉ đang chậm. Qua khoá lạc quan thì không: ai tới trước thắng, ai tới sau bị
 *       từ chối im lặng.</li>
 * </ul>
 */
@InternalAccess("bộ lập lịch @Scheduled trong chính tiến trình này — lời gọi không đến từ mạng, nên không có tầng xác thực nào để đi qua.")
@Service
public class ReapStaleJobsUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReapStaleJobsUseCase.class);

    private final JudgeQueueRepository queue;
    private final SubmissionRepository submissions;
    private final RecordJudgeResultUseCase verdicts;
    private final AppProperties.Judge config;
    private final TransactionTemplate judgeTx;

    public ReapStaleJobsUseCase(JudgeQueueRepository queue, SubmissionRepository submissions,
                                RecordJudgeResultUseCase verdicts, AppProperties properties,
                                @Qualifier("judgeTransactionManager") PlatformTransactionManager tx) {
        this.queue = queue;
        this.submissions = submissions;
        this.verdicts = verdicts;
        this.config = properties.judge();
        this.judgeTx = new TransactionTemplate(tx);
    }

    /**
     * Gọi định kỳ bởi {@code StaleJobReaper} ({@code @Scheduled}, chu kỳ
     * {@code oj.judge.reaper-interval}) — bộ hẹn giờ nằm ở infrastructure, luật nghiệp vụ nằm ở đây.
     *
     * <p><b>Không mang {@code @JudgeTransactional}</b>, và đó là cố ý: mỗi bài bỏ cuộc cần một
     * transaction <i>của riêng nó</i> mở đầu bằng khoá lạc quan (bất biến #7). Bọc cả phương
     * thức thì lượt bỏ cuộc nhập vào transaction ngoài — khoá lạc quan không còn là câu lệnh
     * đầu tiên, và một bài hỏng kéo cả lượt thả lại rollback theo.
     *
     * @return số bài đã xử lý — thả lại cộng bỏ cuộc. {@code > 0} là tín hiệu vận hành: hoặc
     *         có worker vừa chết, hoặc lease đang ngắn hơn thời gian chấm thật của bài nặng nhất
     */
    public int reap() {
        return thaLai() + boCuoc();
    }

    /**
     * Hai câu ghi trong <b>một</b> transaction: thả lease, rồi đưa ảnh chụp trạng thái về
     * {@code QUEUED}. Tách ra hai transaction là có một cửa sổ mà bài đã nằm chờ trong hàng đợi
     * nhưng trang chi tiết vẫn hiện "đang chấm" — và nếu tiến trình chết đúng giữa cửa sổ đó,
     * nó hiện như thế mãi mãi.
     */
    private int thaLai() {
        List<Long> reclaimed = judgeTx.execute(status -> {
            List<Long> ids = queue.reapExpired(config.maxReaps());
            if (!ids.isEmpty()) {
                submissions.markQueued(ids);
            }
            return ids;
        });
        if (reclaimed == null || reclaimed.isEmpty()) {
            return 0;
        }
        log.warn("Reaper thu hồi {} bài quá hạn lease, đã đưa về QUEUED: {}",
                reclaimed.size(), reclaimed);
        return reclaimed.size();
    }

    /**
     * Mỗi bài hết lượt: một transaction ghi verdict riêng, qua khoá lạc quan.
     *
     * <p>★ Bắt lỗi theo TỪNG bài. Không bắt thì bài đầu danh sách ném ở mọi chu kỳ chặn mọi bài
     * xếp sau nó — đúng loại "bài độc" mà trần V16 sinh ra để chặn, chỉ dời từ worker sang
     * chính reaper. Bài hỏng thì transaction của riêng nó rollback, hàng của nó còn nguyên
     * trong {@code judge_queue}, và chu kỳ sau thử lại.
     */
    private int boCuoc() {
        int daGhi = 0;
        for (ExhaustedJob bai : queue.exhaustedJobs(config.maxReaps())) {
            try {
                RecordJudgeResultUseCase.Outcome kq = verdicts.boCuoc(bai.submissionId(),
                        bai.attempt(), config.maxReaps() + 1, bai.claimedAt());
                if (kq == RecordJudgeResultUseCase.Outcome.RECORDED) {
                    daGhi++;
                }
            } catch (RuntimeException e) {
                log.error("Bỏ cuộc submission {} attempt {} hỏng — các bài khác vẫn được xử lý, "
                        + "bài này thử lại ở chu kỳ sau", bai.submissionId(), bai.attempt(), e);
            }
        }
        return daGhi;
    }
}
