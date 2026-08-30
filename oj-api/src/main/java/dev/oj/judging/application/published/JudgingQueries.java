package dev.oj.judging.application.published;

import java.time.Instant;
import java.util.List;

/**
 * ★ Bề mặt <b>công khai</b> của {@code judging} cho {@code contests} và {@code ai}.
 *
 * <h2>Vì sao có package {@code published} thay vì để họ dùng thẳng {@code SubmissionRepository}</h2>
 * Luật ArchUnit 2 cấm module X chạm {@code infrastructure} của module Y, nhưng nó
 * <b>không</b> cấm chạm {@code application.port}. Nếu {@code contests} tiêm thẳng
 * {@code SubmissionRepository} thì mọi phương thức của port ấy — {@code markDone},
 * {@code markQueued}, {@code insert} — trở thành API công khai của {@code judging}, và một
 * ngày nào đó {@code contests} sẽ <i>ghi</i> vào bảng nóng.
 *
 * <p>Package này là chỗ nói rõ: <b>đây là thứ duy nhất module khác được thấy</b>. Nó chỉ đọc,
 * và nó chỉ trả về hình chiếu, không trả về entity ({@code CLAUDE.md} mục 7).
 *
 * <h2>Hình chiếu, không phải entity</h2>
 * {@link ScoredSubmission} cố ý <b>không</b> mang {@code sourceSha256}, {@code compileLog},
 * {@code failedTestOrdinal} hay {@code isolateStatus}. Bảng xếp hạng không cần chúng, và một
 * trường không được truyền đi thì không thể bị lộ nhầm — đây là cùng lập luận đã dùng cho
 * {@code SubmissionEvent} ở M3.
 */
public interface JudgingQueries {

    /**
     * Bài đã chấm xong của một kỳ thi, theo thứ tự {@code id} tăng dần.
     *
     * <h2>★ Thứ tự id là điều kiện để {@code StandingsUpdater} đúng</h2>
     * Nó tiến một watermark duy nhất theo id. Trả về không đúng thứ tự nghĩa là watermark
     * nhảy qua một bài chưa xử lý, và bài đó <b>vĩnh viễn</b> không vào bảng xếp hạng — một
     * lỗi im lặng, phát hiện được duy nhất bởi job đối soát drift (FR-CON-09).
     *
     * @param sauSubmissionId chỉ lấy bài có {@code id} lớn hơn con số này
     * @param gioiHan         kích thước lô. Bất biến #8 — không có truy vấn nào không giới hạn
     */
    List<ScoredSubmission> baiDaChamTrongContest(long contestId, long sauSubmissionId, int gioiHan);

    /**
     * Đọc lại toàn bộ bài đã chấm của <b>một đề</b> trong một kỳ thi — dùng bởi
     * {@code RebuildStandingsJob} (FR-CON-08).
     *
     * <p>Chặn theo từng đề và theo khoảng id, đúng như truy vấn 11 của
     * {@code docs/sql/duong_nong.sql} mô tả: nhờ vậy {@code ix_submissions_problem_recent} cắt
     * gần hết bảng, và {@code submissions.contest_id} <b>không cần index riêng</b> — ngân sách
     * index của bảng nóng vẫn còn chỗ trống.
     */
    List<ScoredSubmission> baiDaChamCuaDe(long contestId, long problemId,
                                          long sauSubmissionId, int gioiHan);

    /**
     * Một bài nộp đã chấm, rút gọn còn thứ bảng xếp hạng cần.
     *
     * @param verdict chuỗi verdict; {@code laAc()} là thứ thể thức thật sự hỏi
     * @param score   điểm đạt được. Với {@code ALL_OR_NOTHING} thì là 0 hoặc {@code maxScore}
     */
    record ScoredSubmission(long submissionId, long userId, long problemId,
                            String verdict, int score, Instant nopLuc) {

        public boolean laAc() {
            return "AC".equals(verdict);
        }
    }
}
