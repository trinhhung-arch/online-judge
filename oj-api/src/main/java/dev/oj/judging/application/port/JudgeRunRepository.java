package dev.oj.judging.application.port;

import dev.oj.judging.domain.JudgeRun;

/**
 * Port ghi lịch sử chấm bài. <b>Chỉ có INSERT</b> — không sửa, không xoá, và đó không phải
 * quy ước mà là quyền: {@code REVOKE UPDATE, DELETE ON judge_runs FROM oj_app} (V9).
 */
public interface JudgeRunRepository {

    /**
     * {@code INSERT ... ON CONFLICT (submission_id, attempt) DO NOTHING}.
     *
     * <p>Đây là <b>lớp chống trùng thứ hai</b> bên cạnh khoá lạc quan trên {@code judge_queue}.
     * Hai lớp cho cùng một bất biến là cố ý: khoá lạc quan bảo vệ theo <i>trình tự</i> (ai
     * xoá được hàng thì người đó ghi), còn khoá chính bảo vệ theo <i>dữ liệu</i> (một cặp
     * submission+attempt chỉ tồn tại một lần, kể cả khi có người viết một đường ghi mới quên
     * mất khoá lạc quan).
     *
     * <p>Hiện thực phân giải {@code hostName} sang {@code judge_hosts.id} bằng một
     * sub-select trong chính câu {@code INSERT}. Không tra bảng trước rồi chèn sau: đó là một
     * lượt round-trip thừa trên đường verdict, và nếu làm bằng {@code UPDATE last_seen_at}
     * thì sáu slot của cùng một máy sẽ tranh khoá trên đúng một dòng {@code judge_hosts}.
     *
     * @return {@code false} nếu attempt này đã có bản ghi — kết quả trùng, bỏ qua im lặng
     */
    boolean insertIfAbsent(JudgeRun run);

    /**
     * Lưu điểm từng nhóm test — FR-PROB-06, bảng {@code judge_run_subtasks} của V4.
     *
     * <p>Gọi trong <b>cùng transaction</b> với {@code insertIfAbsent}: khoá ngoại của
     * {@code judge_run_subtasks} trỏ vào {@code judge_runs(submission_id, attempt)}, nên
     * thứ tự ngược lại là một lần vi phạm khoá ngoại.
     *
     * <p>Danh sách rỗng với đề không chia nhóm — không ghi gì, không phải một lỗi.
     */
    void insertSubtaskResults(long submissionId, int attempt,
                              java.util.List<dev.oj.contract.SubtaskResultDto> subtasks);
}
