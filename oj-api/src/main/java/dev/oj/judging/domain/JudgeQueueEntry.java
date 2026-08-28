package dev.oj.judging.domain;

import java.time.Instant;

/**
 * Một hàng trong {@code judge_queue} — <b>bài đang bay</b>. Vài trăm dòng, không phải vài triệu.
 *
 * <h2>Bảng này là sự thật, RabbitMQ chỉ là đường dẫn</h2>
 * Một bài nộp coi là chấm xong <b>khi và chỉ khi</b> hàng của nó bị xoá khỏi đây, trong cùng
 * transaction với việc ghi verdict. Còn hàng ở đây là còn được chấm lại — và đó là toàn bộ
 * cơ chế bảo đảm R1 ("0 bài mất, tuyệt đối"). Mất sạch RabbitMQ thì dựng lại hàng đợi là một
 * câu {@code SELECT} trên bảng vài trăm dòng ({@code nfrplan.md} 5.1).
 *
 * <p>Cũng vì thế, <b>khoá lạc quan sống ở đây chứ không ở {@code submissions}</b>. Bất biến #7
 * viết {@code WHERE id=? AND attempt=? AND status='JUDGING'}; ngữ nghĩa giữ nguyên, chỗ đặt
 * đổi. Lý do là số đo: {@code attempt} và {@code status} muốn nhanh thì phải index, mà index
 * chúng trên bảng nóng là mất HOT update — 100% xuống 0% ({@code postgres-design.md} mục 4).
 * Quyết định này đã được cả hai người duyệt và ghi ADR (Phần 0 điểm A của {@code build-order.md}).
 *
 * @param priority       {@link DomainRules#PRIORITY_LIVE} hoặc {@link DomainRules#PRIORITY_REJUDGE}
 * @param attempt        số lần đã giao. Tăng lúc <b>claim</b>, không tăng lúc reaper thu hồi
 * @param claimedAt      {@code null} nghĩa là đang chờ một worker
 * @param claimedByHost  {@code judge_hosts.id}. Worker gửi <i>tên</i> máy, API tra ra id —
 *                       worker không biết id trong DB tồn tại (bất biến #3)
 * @param leaseUntil     hết hạn thì reaper nhặt lại. Luôn đi cùng {@code claimedAt}
 * @param ieRetryCount   số lần đã chấm lại vì {@code IE} — FR-SUB-12, trần lấy từ config
 */
public record JudgeQueueEntry(
        long submissionId,
        int priority,
        int attempt,
        Instant enqueuedAt,
        Instant claimedAt,
        Integer claimedByHost,
        Instant leaseUntil,
        int ieRetryCount) {

    public JudgeQueueEntry {
        if (submissionId <= 0) {
            throw new IllegalArgumentException("submissionId phải dương");
        }
        if (priority < 0) {
            throw new IllegalArgumentException("priority không âm: " + priority);
        }
        if (attempt < DomainRules.ATTEMPT_NONE || ieRetryCount < 0) {
            throw new IllegalArgumentException("attempt và ieRetryCount không âm");
        }
        if (enqueuedAt == null) {
            throw new NullPointerException("enqueuedAt");
        }
        // Gương của ck_judge_queue_claim ở V3. Một hàng "đã giao mà không có hạn" là một bài
        // reaper không bao giờ nhặt được: nó biến mất khỏi cả hai index partial và nằm đó
        // mãi mãi. Đây đúng là loại lỗi mà R1 nói không được phép xảy ra.
        if ((claimedAt == null) != (leaseUntil == null)) {
            throw new IllegalStateException(
                    "claimedAt và leaseUntil phải cùng có hoặc cùng không — submission " + submissionId);
        }
        if (claimedAt == null && claimedByHost != null) {
            throw new IllegalStateException("chưa claim thì không có claimedByHost");
        }
    }

    /** Đã có worker cầm bài này. */
    public boolean isClaimed() {
        return claimedAt != null;
    }

    /** Đang chờ được giao — đúng tập hợp mà {@code ix_judge_queue_ready} phục vụ. */
    public boolean isWaiting() {
        return claimedAt == null;
    }

    /**
     * Quá hạn giao → reaper đưa bài về {@code QUEUED}.
     *
     * <p>Dùng {@code isBefore} chứ không {@code !isAfter}: vị từ của reaper trong SQL là
     * {@code lease_until < now()}, và một hàng đúng bằng {@code now()} thì <b>chưa</b> hết
     * hạn. Lệch một dấu bằng ở đây là domain và SQL trả lời khác nhau về cùng một hàng, và
     * chaos test sẽ đỏ theo kiểu rất khó đọc.
     */
    public boolean isLeaseExpired(Instant now) {
        return isClaimed() && leaseUntil.isBefore(now);
    }

    /**
     * Số {@code attempt} mà lần claim kế tiếp sẽ mang. Đây là con số worker phải gửi lại
     * nguyên vẹn trong {@code JudgeResultDto}; sai một đơn vị là kết quả bị bỏ qua im lặng —
     * cơ chế, không phải lỗi.
     */
    public int nextAttempt() {
        return attempt + 1;
    }

    /**
     * FR-SUB-12 — còn được chấm lại vì {@code IE} không?
     *
     * <p>{@code maxRetries} là <b>tham số</b> ({@code oj.judge.max-ie-retries}), không phải
     * hằng số ở đây: nó là con số vận hành sẽ muốn chỉnh, và domain không được import
     * {@code AppProperties} (đó là Spring, luật ArchUnit 1).
     *
     * <p>Nhánh này rẽ <b>trước</b> khoá lạc quan trong {@code RecordJudgeResult}: hết lượt
     * thì mới ghi {@code IE} thật cho người dùng thấy, còn chưa hết thì bài quay lại hàng
     * đợi và không có verdict nào được ghi.
     */
    public boolean canRetryIe(int maxRetries) {
        return ieRetryCount < maxRetries;
    }

    /** Bài nộp trực tiếp của người dùng — luôn được hút cạn trước rejudge (P4, P6). */
    public boolean isLive() {
        return priority == DomainRules.PRIORITY_LIVE;
    }
}
