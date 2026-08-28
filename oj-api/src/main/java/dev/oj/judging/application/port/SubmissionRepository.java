package dev.oj.judging.application.port;

import dev.oj.contract.Verdict;
import dev.oj.judging.domain.JudgeOutcome;
import dev.oj.judging.domain.Submission;
import dev.oj.judging.domain.SubmissionStatus;
import dev.oj.platform.security.Role;
import dev.oj.platform.web.CursorPage;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

/**
 * Port ghi/đọc bảng nóng {@code submissions}.
 *
 * <h2>Chống IDOR nằm TRONG câu query, không nằm ở đây</h2>
 * {@link #findForRequester} nhận {@code requesterId} và {@code role} làm <b>tham số của câu
 * query</b>, chứ không trả về mọi bài rồi để use-case lọc. Đó là khác biệt giữa một hệ thống
 * an toàn và một hệ thống có vẻ an toàn: một câu {@code if} viết đúng ở tầng service vẫn là
 * lỗ hổng nếu câu query lấy về quá nhiều — chỉ cần một người sau này thêm một đường đọc khác
 * quên mất câu {@code if} đó ({@code oj-api/CLAUDE.md} mục 2).
 *
 * <p>Cùng lý do với tên hàm {@code findPublished*} của {@code ProblemRepository}: <b>tên hàm
 * phải nói ra bộ lọc</b>. Không có hàm nào tên {@code findById} trong interface này.
 *
 * <h2>Ba phương thức ghi đều là UPDATE có điều kiện, và đều trả về boolean</h2>
 * Không có {@code save(Submission)}. Ghi cả dòng nghĩa là phải đọc dòng đó lên trước — thêm
 * một lượt round-trip vào đường verdict, và một cửa sổ để hai đường ghi đè lên nhau. Ba
 * phương thức dưới đây là ba câu {@code UPDATE} nhắm đúng vài cột, đúng như
 * {@code postgres-design.md} mục 3 mô tả, và cả ba đều giữ được HOT update vì không cột nào
 * bị chạm có index.
 */
public interface SubmissionRepository {

    /**
     * Chèn một bài nộp mới, {@code status = QUEUED}.
     *
     * @return {@code submissions.id} vừa sinh — {@code INSERT ... RETURNING id}, không phải
     *         một câu {@code SELECT} sau đó
     */
    long insert(NewSubmission submission);

    /**
     * Đọc một bài nộp <b>nếu người gọi được phép thấy nó</b>.
     *
     * <p>Rỗng có hai nghĩa gộp làm một, và đó là cố ý: "không tồn tại" và "tồn tại nhưng
     * không phải của bạn" phải cho ra <b>cùng một 404, cùng một câu chữ</b>. Trả 403 cho
     * trường hợp thứ hai là xác nhận "có tồn tại bài nộp id này" — đủ để dò ra ai đã nộp bài
     * nào, và trong contest thì đó là thông tin không được lộ.
     *
     * @param role vai trò của người gọi. ADMIN thấy mọi bài; USER chỉ thấy bài của chính mình.
     *             SETTER với bài nộp của đề mình là chuyện của M4 — thêm nhánh vào câu query,
     *             không thêm câu {@code if} ở use-case
     */
    Optional<Submission> findForRequester(long id, long requesterId, Role role);

    /**
     * Lịch sử bài nộp của một người — FR-SUB-07. Cursor-based, {@code WHERE id < :cursor
     * ORDER BY id DESC}, chạy trên {@code ix_submissions_user_recent}.
     *
     * <p>Trả về {@link SubmissionListItem} chứ không phải {@link Submission}: trang danh sách
     * cần <b>mã và tiêu đề đề bài</b>, mà hai thứ đó nằm ở bảng {@code problems}. Trả về entity
     * rồi đi tra tên đề cho từng dòng là N+1 — 20 dòng thành 21 câu query, trên đúng trang mà
     * người dùng mở nhiều nhất. Truy vấn 6 của {@code duong_nong.sql} đã {@code JOIN} sẵn.
     *
     * <p>Câu query cũng lọc {@code hidden_at IS NULL}: bài bị ADMIN ẩn (FR-SUB-09) không xuất
     * hiện trong danh sách, kể cả với chính tác giả.
     *
     * @param cursor {@code null} cho trang đầu
     * @param size   đã được {@code clampSize} về [1..50] ở use-case. Câu query lấy
     *               {@code size + 1} dòng để biết còn trang sau mà không phải đếm
     */
    CursorPage<SubmissionListItem> listForUser(long userId, SubmissionFilter filter,
                                               Long cursor, int size);

    /**
     * Thời điểm nộp bài gần nhất — nền cho rate limit 1 bài/10s (FR-SUB-08).
     *
     * <p><b>Chưa ai gọi ở M1</b>, và đó là chủ ý: FR-SUB-08 thuộc M4, nơi {@code RateLimiter}
     * chạy trên Redis. Phương thức này là <b>đường dự phòng khi Redis chết</b> (truy vấn 7,
     * {@code nfrplan.md} 7.2 "degraded mode") — nó đọc index-only trên
     * {@code ix_submissions_user_recent}, không chạm heap.
     */
    Optional<Instant> lastSubmittedAt(long userId);

    /**
     * {@code QUEUED -> JUDGING} kèm {@code attempt} mới. Gọi ngay sau khi claim thành công.
     *
     * @return {@code false} nếu không dòng nào khớp — bài đã bị đường khác đổi trạng thái
     */
    boolean markJudging(long submissionId, int attempt);

    /**
     * {@code JUDGING -> DONE} kèm verdict. Gọi <b>sau</b> khoá lạc quan trên
     * {@code judge_queue}, không bao giờ trước.
     *
     * <p>Câu {@code UPDATE} mang theo {@code AND attempt = :attempt AND status = 'JUDGING'} —
     * cùng ngữ nghĩa với bất biến #7, đặt ở đây làm lớp bảo vệ thứ ba sau khoá lạc quan và
     * khoá chính của {@code judge_runs}. Ba lớp cho một bất biến nghe như thừa, cho tới ngày
     * có người thêm một đường ghi verdict thứ hai mà quên mất hai lớp kia.
     */
    boolean markDone(long submissionId, int attempt, JudgeOutcome outcome, Instant judgedAt);

    /**
     * {@code JUDGING -> QUEUED} cho các bài reaper vừa thu hồi. <b>Không đụng tới
     * {@code attempt}</b> — lần claim kế tiếp mới tăng ({@code postgres-design.md} mục 3).
     *
     * <p>Nhận cả lô vì reaper luôn xử lý theo lô: một worker chết mang theo cả 6 slot của nó,
     * và sáu câu {@code UPDATE} rời nhau là sáu lượt round-trip trên pool chỉ có 6 connection.
     *
     * @return số dòng đã đổi, dùng cho log và metric
     */
    int markQueued(Collection<Long> submissionIds);

    /**
     * Một bài nộp đang được tạo — tham số của {@link #insert}.
     *
     * <p>Không phải {@code Submission}: bản ghi domain đó đòi {@code id > 0}, mà id thì do
     * Postgres sinh. Đây đúng là ranh giới nơi "một bài nộp sắp có" khác "một bài nộp đã có".
     *
     * @param testdataVersion đóng dấu phiên bản testdata <b>tại lúc nộp</b>. Sửa testdata tạo
     *                        version mới chứ không ghi đè (FR-PROB-10), nên con số này là thứ
     *                        duy nhất trả lời được vì sao verdict hôm nay khác hôm qua
     */
    record NewSubmission(
            long userId,
            long problemId,
            Long contestId,
            int languageId,
            String sourceSha256,
            int sourceBytes,
            int testdataVersion) {
    }

    /**
     * Một dòng của trang lịch sử — hình chiếu của truy vấn 6, không phải entity.
     *
     * <p>Cố ý <b>không</b> mang {@code sourceSha256} hay {@code failedTestOrdinal}: trang danh
     * sách không cần chúng, và một trường không được truyền đi thì không thể bị lộ nhầm. Số
     * thứ tự test sai còn phải qua bộ lọc {@code feedback_level} (FR-PROB-07) trước khi tới
     * người dùng, và trang danh sách không phải chỗ làm việc đó.
     *
     * @param verdict {@code null} khi bài chưa chấm xong
     */
    record SubmissionListItem(
            long id,
            long problemId,
            String problemCode,
            String problemTitle,
            int languageId,
            SubmissionStatus status,
            Verdict verdict,
            Integer score,
            Integer timeMs,
            Integer memoryKb,
            Instant createdAt) {
    }

    /**
     * Bộ lọc của FR-SUB-07: theo đề, theo verdict, theo ngôn ngữ. {@code null} là không lọc.
     *
     * <p>Hiện thực dùng {@code (:x IS NULL OR cot = :x)} trong <b>một câu SQL hằng</b>, không
     * nối chuỗi để dựng mệnh đề {@code WHERE} động — bất biến #5, và cũng để planner còn
     * dùng lại được prepared statement.
     */
    record SubmissionFilter(Long problemId, Verdict verdict, Integer languageId) {

        private static final SubmissionFilter NONE = new SubmissionFilter(null, null, null);

        public static SubmissionFilter none() {
            return NONE;
        }
    }
}
