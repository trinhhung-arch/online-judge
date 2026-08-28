package dev.oj.judging.domain;

/**
 * Vòng đời của một bài nộp — FR-SUB-03. Khớp
 * {@code CHECK (status IN ('QUEUED','JUDGING','DONE'))} ở V3.
 *
 * <h2>Đây là ảnh chụp, không phải sự thật</h2>
 * Sự thật về "bài này đã chấm xong chưa" nằm ở <b>sự tồn tại của một hàng trong
 * {@code judge_queue}</b>, không ở cột này ({@code postgres-design.md} mục 1). Cột
 * {@code status} tồn tại để trang chi tiết không phải join sang bảng hàng đợi, và cố ý
 * <b>không được đánh index</b> — đánh index lên nó là mất HOT update trên bảng nóng, đo
 * thật: 100% xuống 0% ({@code postgres-design.md} mục 4).
 *
 * <p>Hệ quả cần nhớ: một bài {@code DONE} mà vẫn còn hàng trong {@code judge_queue} là bài
 * <b>chưa</b> xong. Đừng bao giờ viết một câu hỏi vận hành ("còn bao nhiêu bài đang chờ?")
 * dựa trên cột này — đếm trên {@code judge_queue}, bất biến #8 và {@code postgres-design.md}
 * mục 15.
 *
 * <h2>Không có DELETED, không có CANCELLED</h2>
 * Không ai xoá được bài nộp, kể cả ADMIN, kể cả qua SQL — {@code REVOKE DELETE, TRUNCATE ON
 * submissions FROM oj_app} ở V9. FR-SUB-09 là một quyền ở tầng Postgres, không phải một nút
 * bị ẩn. ADMIN chỉ <i>ẩn</i> được, và đó là hai cột khác ({@code hidden_at}/{@code hidden_by}).
 */
public enum SubmissionStatus {

    /** Đã ghi vào DB, đang chờ worker nhận. Trạng thái ngay sau khi API trả 202. */
    QUEUED,

    /**
     * Một worker đã claim và đang chấm.
     *
     * <p>Kẹt ở đây quá {@code oj.judge.lease} thì reaper đưa về {@link #QUEUED} — một cơ chế
     * cứu năm loại sự cố: worker chết, queue chết, publish thất bại sau khi commit, mạng đứt,
     * deploy giữa chừng ({@code nfrplan.md} 5.1).
     */
    JUDGING,

    /**
     * Đã có verdict. <b>Không phải trạng thái cuối</b> — rejudge (FR-ADM-01) đưa bài quay lại
     * hàng đợi với một {@code attempt} mới, và verdict cũ vẫn nằm nguyên trong
     * {@code judge_runs} để đối chiếu.
     */
    DONE;

    /**
     * Chuyển từ trạng thái này sang {@code next} có hợp lệ không?
     *
     * <p>Bốn đường được phép, và mỗi đường có đúng một người gọi:
     * <pre>
     *   QUEUED  -> JUDGING   claim      (attempt tăng ở đây, không ở chỗ khác)
     *   JUDGING -> DONE      result     (kèm khoá lạc quan trên judge_queue)
     *   JUDGING -> QUEUED    reaper     (attempt KHÔNG tăng ở đây)
     *   DONE    -> QUEUED    rejudge    (M6 — verdict cũ giữ nguyên cho tới attempt sau)
     * </pre>
     *
     * <p>Rejudge ở M6 phải đưa bài về {@link #QUEUED} <b>lúc enqueue</b>, không nhảy thẳng
     * {@code DONE -> JUDGING} lúc claim. Nếu không, trang chi tiết hiện "đã chấm xong" cho một
     * bài đang nằm trong hàng đợi, và người dùng nộp lại vì tưởng hệ thống quên mất họ.
     *
     * <p><b>{@code QUEUED -> DONE} bị cấm</b>, và đó là điểm chính của bảng này: một verdict
     * chỉ được ghi cho bài mà một worker đang thật sự cầm. <b>{@code DONE -> DONE} cũng bị
     * cấm</b> — đó là hình chiếu của bất biến #7 vào domain, tuy lớp chặn thật sự nằm ở câu
     * {@code DELETE FROM judge_queue ... AND attempt=?} chạy trước đó.
     */
    public boolean canTransitionTo(SubmissionStatus next) {
        return switch (this) {
            case QUEUED -> next == JUDGING;
            case JUDGING -> next != JUDGING;          // -> DONE (kết quả) hoặc -> QUEUED (reaper)
            case DONE -> next == QUEUED;              // rejudge, M6
        };
    }

    /** Còn nằm trong đường chấm — người dùng vẫn đang chờ một verdict mới. */
    public boolean isPending() {
        return this != DONE;
    }

    public static SubmissionStatus fromCode(String code) {
        for (SubmissionStatus status : values()) {
            if (status.name().equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("submission status không hợp lệ: " + code);
    }
}
