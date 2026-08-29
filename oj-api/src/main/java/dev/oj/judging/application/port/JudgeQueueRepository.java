package dev.oj.judging.application.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Port của <b>hàng đợi bền</b> {@code judge_queue} — bảng vài trăm dòng quyết định R1 và R2.
 *
 * <p>Toàn bộ interface này là bốn câu SQL đã được đo trên Postgres 16 ({@code duong_nong.sql}).
 * Viết lại "cho gọn" là cách nhanh nhất để mất {@code SKIP LOCKED}, mất {@code RETURNING},
 * hoặc thêm một {@code COUNT(*)} — {@code docs/build-order.md} Bước M1-7 nói thẳng điều đó.
 */
public interface JudgeQueueRepository {

    /**
     * Đưa một bài vào hàng đợi. Chạy <b>trong cùng transaction</b> với {@code INSERT submissions}:
     * commit rồi thì bài chắc chắn được chấm, kể cả khi RabbitMQ chết ngay sau đó.
     *
     * @param priority {@code DomainRules.PRIORITY_LIVE} hoặc {@code PRIORITY_REJUDGE}
     */
    void enqueue(long submissionId, int priority);

    /**
     * Worker xin việc. Một câu {@code UPDATE ... WHERE submission_id = (SELECT ... FOR UPDATE
     * SKIP LOCKED LIMIT 1) RETURNING ...} — không phải {@code SELECT} rồi {@code UPDATE}.
     *
     * <p><b>{@code SKIP LOCKED} là thứ làm cho hai worker không bao giờ nhận cùng một bài</b>
     * mà không cần khoá phân tán nào. Bỏ nó đi thì hai worker xếp hàng chờ nhau và throughput
     * sập về một luồng; thay nó bằng {@code SELECT} trước rồi {@code UPDATE} sau thì có cửa
     * sổ để cả hai cùng thấy một dòng.
     *
     * <p>{@code attempt} tăng <b>ở đây</b>. Chỉ riêng điều đó đã vô hiệu hoá kết quả trả về
     * muộn của một worker đã bị reaper thu hồi ({@code postgres-design.md} mục 3).
     *
     * @return rỗng khi hàng đợi rỗng — API trả {@code 204}, worker ngủ một nhịp rồi xin lại
     */
    Optional<ClaimedJob> claim(String hostName, int leaseSeconds);

    /**
     * ★ <b>Khoá lạc quan.</b> {@code DELETE FROM judge_queue WHERE submission_id = :id AND
     * attempt = :attempt} — câu lệnh <b>đầu tiên</b> của transaction ghi verdict (bất biến #7).
     *
     * <p>0 dòng nghĩa là kết quả này thuộc về một attempt đã chết (reaper đã thu hồi) hoặc là
     * bản giao trùng của RabbitMQ. Bỏ qua <b>im lặng</b>: không ghi gì, không ném lỗi, không
     * trả lỗi cho worker.
     *
     * <p>Trả về dữ liệu chứ không phải {@code boolean} (bản kế hoạch viết {@code boolean}) vì
     * {@code judge_runs} cần {@code language_id} và {@code testdata_version}, mà hai cột đó
     * nằm ở {@code submissions}. Một câu {@code DELETE ... USING submissions ... RETURNING}
     * lấy luôn cả hai; kiểu {@code boolean} thì buộc phải thêm một câu {@code SELECT} nữa vào
     * đúng transaction ngắn nhất và nóng nhất của hệ thống.
     *
     * @return rỗng nếu khoá lạc quan từ chối
     */
    Optional<ReleasedSubmission> releaseWithOptimisticLock(long submissionId, int attempt);

    /**
     * Thu hồi mọi lease đã hết hạn: {@code UPDATE judge_queue SET claimed_at = NULL,
     * lease_until = NULL, claimed_by_host = NULL WHERE lease_until < now() RETURNING submission_id}.
     *
     * <p><b>Không tăng {@code attempt} ở đây.</b> Lần claim kế tiếp mới tăng — nếu tăng ở cả
     * hai chỗ thì một bài bị thu hồi sẽ nhảy hai số, và kết quả của worker cũ (mang số cũ) vẫn
     * bị loại đúng như mong muốn nhưng {@code judge_runs} sẽ có lỗ hổng số thứ tự không ai
     * giải thích được.
     *
     * @return id các bài vừa được thả — người gọi đưa {@code submissions.status} về QUEUED
     */
    List<Long> reapExpired();

    /**
     * FR-SUB-12 — bài {@code IE} được chấm lại tối đa {@code maxRetries} lần.
     *
     * <p>Một câu {@code UPDATE judge_queue SET claimed_at = NULL, ie_retry_count =
     * ie_retry_count + 1 WHERE submission_id = :id AND attempt = :attempt AND ie_retry_count
     * < :maxRetries}. Điều kiện {@code attempt} khiến nó mang luôn tính chất của khoá lạc
     * quan, nên nhánh IE rẽ trước {@link #releaseWithOptimisticLock} mà không mất an toàn.
     *
     * @return {@code true} nghĩa là bài đã quay lại hàng đợi và <b>không có verdict nào được
     *         ghi</b>. {@code false} nghĩa là hết lượt — ghi {@code IE} thật cho người dùng thấy
     */
    boolean retryIe(long submissionId, int attempt, int maxRetries);

    /**
     * Độ sâu hàng đợi cho trang trạng thái công khai (FR-ADM-05) và metric P6.
     *
     * <p>Đếm trên bảng này, <b>không bao giờ {@code COUNT(*)} trên {@code submissions}</b> —
     * bảng đó có hàng triệu dòng còn bảng này có vài trăm ({@code postgres-design.md} mục 15).
     */
    QueueStats queueDepth();

    /**
     * Một job vừa được giao. Đây là toàn bộ dữ liệu {@code ClaimJudgeJobUseCase} cần từ phía
     * hàng đợi; phần còn lại của {@code JudgeJobDto} đến từ {@code JudgeSpecRepository}.
     *
     * <p><b>Chứa mã nguồn người dùng</b> (quyết định B: worker nhận source qua response của
     * {@code claim}, nên nó vẫn không cần {@code DataSource} — bất biến #3). Vì thế
     * {@link #toString()} bị ghi đè.
     */
    record ClaimedJob(
            long submissionId,
            int attempt,
            long problemId,
            int testdataVersion,
            String sourceSha256,
            String sourceContent,
            LanguageSpec language) {

        /**
         * Phần dữ liệu ngôn ngữ đi kèm ngay trong câu claim (một {@code JOIN}, không phải một
         * lượt round-trip thứ hai trên pool 6 connection).
         *
         * <p>Ba hệ số cuối là lý do bảng {@code languages} tồn tại: thêm một ngôn ngữ phải là
         * <b>một dòng seed, không dòng code nào</b> (M4-nfr). Chúng được nhân vào giới hạn ở
         * API — worker chỉ nhân thêm {@code host_factor} của máy nó.
         */
        public record LanguageSpec(
                String code,
                String sourceExtension,
                String compileCommand,
                String runCommand,
                int compileTimeLimitMs,
                int compileMemoryKb,
                BigDecimal timeMultiplier,
                int startupOverheadMs,
                int memoryOverheadKb) {
        }

        @Override
        public String toString() {
            return "ClaimedJob[submissionId=" + submissionId + ", attempt=" + attempt
                    + ", problemId=" + problemId + ", language="
                    + (language == null ? null : language.code()) + "]";
        }
    }

    /**
     * Những gì câu khoá lạc quan trả về khi nó thắng — vừa đủ để dựng một {@code JudgeRun}.
     *
     * @param testdataVersion phiên bản mà chính attempt này đã dùng, đọc từ {@code submissions}
     */
    record ReleasedSubmission(long submissionId, int attempt, int languageId, int testdataVersion) {
    }

    /**
     * @param waiting          đang chờ worker ({@code claimed_at IS NULL})
     * @param claimed          đang được chấm
     * @param oldestEnqueuedAt bài chờ lâu nhất; {@code null} khi hàng đợi rỗng. Đây là đầu vào
     *                         của metric {@code queue_wait} (P6: p95 < 5s) và của câu
     *                         "thời gian chờ ước tính" trên trang trạng thái
     */
    record QueueStats(int waiting, int claimed, Instant oldestEnqueuedAt) {

        public boolean isEmpty() {
            return waiting == 0 && claimed == 0;
        }
    }
}
