package dev.oj.judging.application.port;

import dev.oj.judging.domain.RejudgeJob;

import java.util.List;

/**
 * Ba câu SQL của chấm lại hàng loạt — FR-ADM-01, Bước 6.3.
 *
 * <h2>Vì sao một port riêng thay vì thêm phương thức vào {@link JudgeQueueRepository}</h2>
 * {@code JudgeQueueRepository} là port của <b>đường nóng</b>: bốn câu đã đo, mỗi câu chạy ở
 * mỗi lần nộp bài hoặc mỗi lượt claim. Ba câu dưới đây chạy vài lần một phút từ một job nền.
 * Trộn chúng vào một interface là mời người bảo trì sau này gọi nhầm một câu quét bảng từ
 * trong đường 300ms — và tên hàm sẽ không cảnh báo gì cả.
 *
 * <p>Đây là cùng lập luận đã tách {@code ProblemAuthoringRepository} khỏi
 * {@code ProblemRepository} ở M4: <b>một port là một lời hứa về chi phí</b>, không chỉ về dữ liệu.
 */
public interface RejudgeRepository {

    /**
     * Ảnh chụp hàng đợi <b>tách theo mức ưu tiên</b> — đầu vào của
     * {@link RejudgeJob#suatConLai}.
     *
     * <p>Tách theo ưu tiên là điều kiện để cái phanh đúng: xem javadoc của {@code RejudgeJob}
     * về việc job tự đạp phanh của chính mình.
     */
    RejudgeJob.NhipHangDoi doNhip();

    /**
     * Id các bài nộp của một đề, {@code id > sauId}, tăng dần, tối đa {@code gioiHan} dòng.
     *
     * <p>Tăng dần và có cursor vì {@code cursor_state} của job lưu đúng một con số
     * {@code lastSubmissionId} — thứ làm cho job chạy tiếp được sau restart (Quy tắc 5).
     * Giảm dần thì cursor không diễn đạt được "đã xong tới đâu" khi có bài mới chèn vào giữa.
     *
     * <p>Chạy trên {@code ix_submissions_problem_recent}. Bất biến #8 — {@code gioiHan} bắt buộc.
     */
    List<Long> baiCuaDe(long problemId, long sauId, int gioiHan);

    /**
     * ★ Đưa một lô bài trở lại hàng đợi với {@code priority = 10}.
     *
     * <h2>Ba điều câu này PHẢI làm, và một điều nó phải KHÔNG làm</h2>
     * <ul>
     *   <li><b>Bỏ qua bài đang trong hàng đợi.</b> {@code ON CONFLICT DO NOTHING} trên khoá
     *       chính {@code judge_queue.submission_id}: một bài đang chờ chấm hoặc đang được
     *       chấm không được đẩy vào lần nữa. Không có mệnh đề này thì rejudge một đề ngay sau
     *       một đợt nộp dồn sẽ <b>huỷ lượt chấm đang chạy</b> của chính những bài đó.</li>
     *   <li><b>Đưa {@code submissions.status} về {@code QUEUED}.</b> Cùng transaction, nếu
     *       không thì bảng nói bài đã {@code DONE} trong khi hàng đợi nói nó đang chờ.</li>
     *   <li><b>Giữ nguyên verdict cũ.</b> {@code ck_submissions_done} cố ý không ép chiều
     *       ngược lại (xem V3), nên UI hiện được "WA · đang chấm lại" thay vì một ô trống.
     *       Đây là FR-ADM-01: <i>verdict cũ không bị ghi đè mà lưu thành attempt mới</i> —
     *       {@code judge_runs} có khoá chính {@code (submission_id, attempt)}, nên lượt chấm
     *       mới ghi thêm một dòng chứ không đè dòng cũ.</li>
     *   <li><b>KHÔNG đụng tới {@code attempt}.</b> Lần claim kế tiếp mới tăng — cùng luật với
     *       reaper ({@code postgres-design.md} mục 3). Tăng ở đây là làm {@code judge_runs}
     *       thủng một số thứ tự mà không ai giải thích được.</li>
     * </ul>
     *
     * @return id các bài <b>thật sự</b> vào được hàng đợi — ít hơn {@code ids} khi có bài
     *         đang chấm dở. Trả danh sách chứ không phải số đếm vì người gọi còn phải gõ cửa
     *         cho đúng những bài đó ({@code JudgeJobPublisher.publishRejudgeEnqueued}); một
     *         con số thì không nói được gõ cửa cho ai
     */
    List<Long> dayVaoHangDoi(List<Long> ids);

    /**
     * Tổng số bài nộp của một đề — chỉ để thanh tiến độ có mẫu số.
     *
     * <p><b>Đây là {@code COUNT(*)} duy nhất được phép trên {@code submissions}</b>, và nó
     * được phép vì hai lý do: nó lọc theo {@code problem_id} nên chạy index-only trên
     * {@code ix_submissions_problem_recent} thay vì quét bảng, và nó chạy <b>một lần</b> lúc
     * job bắt đầu — không phải trên đường nộp bài, không phải trên một endpoint danh sách
     * ({@code oj-api/CLAUDE.md} mục 3).
     *
     * <p>Không có nó thì {@link dev.oj.platform.jobs.JobContext#tienDo} phải truyền
     * {@code null} cho tổng, và người vận hành nhìn một job chạy 30 phút mà không biết nó
     * đang ở đâu — đúng thứ Quy tắc 5 sinh ra để tránh.
     */
    int demBaiCuaDe(long problemId);
}
