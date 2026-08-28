package dev.oj.judging.domain;

import dev.oj.contract.Sha256;

import java.time.Instant;

/**
 * Một lần nộp bài — bảng {@code submissions} ở V3, FR-SUB-01..12.
 *
 * <p>Từ vựng ({@code CLAUDE.md} mục 10): đây là một <b>submission</b>, không phải "solution"
 * hay "answer"; thứ nó nhận về là một <b>verdict</b>, không phải "result".
 *
 * <h2>Bất biến sống ở đây, không ở controller</h2>
 * <ul>
 *   <li><b>{@code attempt} chỉ tăng.</b> {@link #markJudging(int)} từ chối mọi số không lớn
 *       hơn số hiện tại. Chỉ riêng điều này đã vô hiệu hoá kết quả trả về muộn của một worker
 *       đã bị reaper thu hồi — không cần thêm cơ chế nào ({@code postgres-design.md} mục 3).</li>
 *   <li><b>Không ghi verdict cho bài chưa được claim.</b> {@link #markDone} đòi
 *       {@link SubmissionStatus#JUDGING}, và từ chối verdict {@code null}.</li>
 *   <li><b>Không có {@code delete()}.</b> Không ai xoá được bài nộp (FR-SUB-09) — ADMIN chỉ
 *       ẩn, và hàng rào thật nằm ở {@code REVOKE DELETE, TRUNCATE ... FROM oj_app} (V9).
 *       Một phương thức {@code delete()} ở đây sẽ là lời mời viết câu SQL tương ứng.</li>
 * </ul>
 *
 * <p>Record bất biến: mỗi bước chuyển trả về một {@code Submission} mới. Không có setter nào
 * để gọi nhầm hai lần, và mọi bước chuyển đều test được bằng JUnit trần dưới một giây.
 *
 * @param contestId        {@code null} nếu nộp ngoài kỳ thi. Khoá ngoại gắn ở V6
 * @param sourceSha256     trỏ tới {@link SourceBlob} — bảng nóng không chứa mã nguồn
 * @param attempt          lần chấm thứ mấy. {@code 0} nghĩa là chưa worker nào cầm
 * @param testdataVersion  phiên bản testdata của attempt hiện tại. Giữ lại để verdict hôm nay
 *                         khác hôm qua thì còn truy được vì sao (FR-PROB-10)
 * @param outcome          ảnh chụp kết quả gần nhất; {@code null} khi chưa từng chấm xong
 * @param hiddenAt         FR-SUB-09 — ADMIN ẩn, không xoá. Luôn đi cùng {@code hiddenBy}
 */
public record Submission(
        long id,
        long userId,
        long problemId,
        Long contestId,
        int languageId,
        String sourceSha256,
        int sourceBytes,
        Instant createdAt,
        SubmissionStatus status,
        int attempt,
        Integer testdataVersion,
        JudgeOutcome outcome,
        Instant judgedAt,
        Instant hiddenAt,
        Long hiddenBy) {

    public Submission {
        if (id <= 0 || userId <= 0 || problemId <= 0 || languageId <= 0) {
            throw new IllegalArgumentException("id, userId, problemId, languageId phải dương");
        }
        if (contestId != null && contestId <= 0) {
            throw new IllegalArgumentException("contestId phải dương hoặc null");
        }
        if (!Sha256.isHex(sourceSha256)) {
            throw new IllegalArgumentException("sourceSha256 phải là 64 ký tự hex chữ thường");
        }
        if (sourceBytes <= 0 || sourceBytes > DomainRules.MAX_SOURCE_BYTES) {
            throw new IllegalArgumentException("sourceBytes ngoài (0.." + DomainRules.MAX_SOURCE_BYTES + "]");
        }
        if (createdAt == null || status == null) {
            throw new NullPointerException("createdAt và status đều bắt buộc");
        }
        if (attempt < DomainRules.ATTEMPT_NONE) {
            throw new IllegalArgumentException("attempt không âm: " + attempt);
        }
        // Gương của ck_submissions_done ở V3. Chiều ngược lại CỐ Ý không ép: rejudge đưa bài
        // về QUEUED/JUDGING mà vẫn giữ outcome cũ, để UI hiện "WA · đang chấm lại" thay vì
        // một ô trống làm người dùng tưởng bài mình bốc hơi.
        if (status == SubmissionStatus.DONE && outcome == null) {
            throw new IllegalStateException("status=DONE nhưng không có outcome — id " + id);
        }
        if ((outcome == null) != (judgedAt == null)) {
            throw new IllegalStateException("outcome và judgedAt phải cùng có hoặc cùng không");
        }
        // Gương của ck_submissions_hidden ở V3: ẩn thì phải biết ai ẩn (FR-SUB-09 + audit_log).
        if ((hiddenAt == null) != (hiddenBy == null)) {
            throw new IllegalStateException("hiddenAt và hiddenBy phải cùng có hoặc cùng không");
        }
    }

    /**
     * Worker vừa claim bài này — {@code POST /internal/judge/claim}.
     *
     * <p>{@code attempt} tăng ở <b>đây và chỉ ở đây</b>. Reaper thu hồi một bài thì không
     * tăng gì cả ({@link #markQueued()}); lần claim kế tiếp mới tăng. Nhờ vậy kết quả của
     * attempt cũ về muộn sẽ mang một số không còn khớp, và khoá lạc quan trên
     * {@code judge_queue} loại nó đi mà không cần biết chuyện gì đã xảy ra.
     *
     * <p>Giữ nguyên {@link #outcome} của lần chấm trước — xem ghi chú ở compact constructor.
     *
     * @throws IllegalArgumentException nếu {@code newAttempt} không lớn hơn {@code attempt}
     *                                  hiện tại. Đây là bug phía API, không phải lỗi người
     *                                  dùng: một lời gọi lùi số là dấu hiệu hai đường ghi
     *                                  đang tranh nhau cùng một bài
     */
    public Submission markJudging(int newAttempt) {
        if (newAttempt <= attempt) {
            throw new IllegalArgumentException(
                    "attempt chỉ tăng: " + attempt + " -> " + newAttempt + " (submission " + id + ")");
        }
        requireTransitionTo(SubmissionStatus.JUDGING);
        return new Submission(id, userId, problemId, contestId, languageId, sourceSha256,
                sourceBytes, createdAt, SubmissionStatus.JUDGING, newAttempt, testdataVersion,
                outcome, judgedAt, hiddenAt, hiddenBy);
    }

    /**
     * Ghi verdict — {@code POST /internal/judge/result}, sau khi khoá lạc quan đã trả về
     * đúng một dòng.
     *
     * <p><b>Đây không phải lớp chống trùng.</b> Lớp đó là câu
     * {@code DELETE FROM judge_queue WHERE submission_id=? AND attempt=?} chạy trước, cộng
     * khoá chính {@code (submission_id, attempt)} của {@code judge_runs} (bất biến #7).
     * Điều kiện {@code status = JUDGING} ở đây là hàng rào thứ ba, và nó bắt đúng một loại
     * lỗi mà hai hàng rào kia không thấy: một đường ghi mới nào đó gọi thẳng
     * {@code markDone} mà quên đi qua khoá lạc quan.
     *
     * @param judgedAt thời điểm ghi nhận. Truyền vào chứ không gọi {@code Instant.now()}:
     *                 domain không có đồng hồ, và một bước chuyển phụ thuộc giờ hệ thống là
     *                 một bước chuyển không test được
     */
    public Submission markDone(JudgeOutcome result, Instant judgedAt) {
        if (result == null) {
            throw new IllegalArgumentException("markDone cần một outcome — submission " + id);
        }
        if (judgedAt == null) {
            throw new NullPointerException("judgedAt");
        }
        requireTransitionTo(SubmissionStatus.DONE);
        return new Submission(id, userId, problemId, contestId, languageId, sourceSha256,
                sourceBytes, createdAt, SubmissionStatus.DONE, attempt, testdataVersion,
                result, judgedAt, hiddenAt, hiddenBy);
    }

    /**
     * Quay lại hàng đợi. Hai người gọi: <b>reaper</b> khi lease hết hạn (M1) và
     * <b>rejudge</b> khi ADMIN cho chấm lại (M6, FR-ADM-01).
     *
     * <p>{@code attempt} <b>không</b> tăng ở đây — {@code ReapStaleJobsUseCaseTest} kiểm đúng
     * điều đó. {@link #outcome} cũng giữ nguyên: trang chi tiết hiện "WA · đang chấm lại".
     */
    public Submission markQueued() {
        requireTransitionTo(SubmissionStatus.QUEUED);
        return new Submission(id, userId, problemId, contestId, languageId, sourceSha256,
                sourceBytes, createdAt, SubmissionStatus.QUEUED, attempt, testdataVersion,
                outcome, judgedAt, hiddenAt, hiddenBy);
    }

    /** FR-SUB-09 — ADMIN đã ẩn bài này. Ẩn, không xoá: mọi bảng xếp hạng lịch sử vẫn đúng. */
    public boolean isHidden() {
        return hiddenAt != null;
    }

    /** Nộp trong một kỳ thi → ma trận hiển thị siết chặt hơn hẳn ({@code frplan.md} Quy tắc 3). */
    public boolean isInContest() {
        return contestId != null;
    }

    /** Người dùng vẫn đang chờ một verdict mới cho bài này. */
    public boolean isPending() {
        return status.isPending();
    }

    private void requireTransitionTo(SubmissionStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException(
                    "submission " + id + ": không được chuyển " + status + " -> " + next);
        }
    }
}
