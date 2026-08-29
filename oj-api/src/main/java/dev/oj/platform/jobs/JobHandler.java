package dev.oj.platform.jobs;

/**
 * Việc thật của một loại job. <b>Khung ở {@code platform}, việc ở module sở hữu dữ liệu</b>
 * ({@code cau-truc-source.md} mục 3.5).
 *
 * <h2>Vì sao interface này là cách duy nhất giữ được luật ArchUnit 3b</h2>
 * {@link JobRunner} phải chạy được {@code TestdataImportJob} của {@code problems} và
 * {@code RejudgeJob} của {@code judging}. Nếu nó gọi thẳng thì {@code platform} import
 * nghiệp vụ — và lúc đó nó không còn là nền, nó là một module nghiệp vụ thứ bảy.
 *
 * <p>Với interface này thì chiều phụ thuộc đảo lại: {@code problems} biết {@code platform},
 * Spring tiêm {@code List<JobHandler>} vào runner, và {@code platform} không biết ai hiện
 * thực mình.
 *
 * <h2>Hợp đồng mà mọi handler phải giữ</h2>
 * <ol>
 *   <li><b>Idempotent theo {@code cursorState}.</b> Chạy lại từ một vị trí đã lưu không được
 *       làm hỏng phần đã xong. Job <i>sẽ</i> bị chạy lại — đó là cả điểm của Quy tắc 5.</li>
 *   <li><b>Gọi {@link JobContext#tienDo} đều đặn.</b> Nó vừa là tiến độ vừa là heartbeat;
 *       im lặng quá lâu là bị nhặt lại và chạy song song với chính mình.</li>
 *   <li><b>Ném ngoại lệ khi hỏng thật.</b> Đừng nuốt rồi trả về bình thường — job sẽ được
 *       đánh dấu {@code DONE} trong khi việc chưa xong, và không ai biết.</li>
 * </ol>
 */
public interface JobHandler {

    JobType type();

    /**
     * @throws JobsException {@code job.da_bi_huy} nếu dừng vì bị huỷ — {@link JobRunner} phân
     *         biệt trường hợp này với lỗi thật
     */
    void chay(JobContext ctx);
}
