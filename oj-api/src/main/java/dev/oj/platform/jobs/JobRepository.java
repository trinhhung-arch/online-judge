package dev.oj.platform.jobs;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Cổng ra bảng {@code jobs} và {@code job_events} (V6).
 *
 * <h2>{@link #claim} là khoá lạc quan, cùng khuôn với {@code judge_queue}</h2>
 * Một câu {@code UPDATE ... WHERE id = ? AND lease_until IS NULL OR lease_until < now()} trả
 * về 0 dòng nghĩa là instance khác đã giành được — bỏ qua im lặng, không thử lại. Đây đúng
 * lập luận của bất biến #7, áp cho một bảng khác: <b>hai instance API cùng chạy một job</b>
 * với một job nạp testdata nghĩa là hai lần ghi cùng một testcase.
 */
public interface JobRepository {

    /**
     * @return id job vừa tạo
     * @throws JobsException {@code job.dang_chay} nếu chạm {@code ux_jobs_one_active_per_entity}
     */
    long tao(JobType type, Map<String, Object> params, Long createdBy);

    /**
     * Dùng bởi {@link JobRunner}. <b>Không có điều kiện chủ sở hữu</b> — runner không phải
     * một người dùng.
     */
    Optional<Job> timTheoId(long jobId);

    /**
     * ★ Đường của người dùng: điều kiện chủ sở hữu nằm <b>trong câu query</b>, không phải
     * trong một câu {@code if} sau khi đã load ({@code oj-api/CLAUDE.md} mục 2, Bước 4.8).
     *
     * <p>Nhờ thế job của SETTER khác trả về rỗng một cách tự nhiên và use-case ném
     * {@code NOT_FOUND} — 404, không phải 403. 403 ở đây là xác nhận "có một job id này tồn
     * tại", đủ để dò ra ai đang nạp testdata cho đề nào.
     */
    Optional<Job> timChoNguoiGoi(long jobId, long requesterId, boolean laAdmin);

    /** Job của một người, mới nhất trước. ADMIN truyền {@code null} để thấy tất cả. */
    List<Job> ganDay(Long createdBy, int gioiHan);

    /**
     * Giành quyền chạy một job đang chờ, đặt {@code RUNNING} và một lease mới.
     *
     * @return job đã giành được, hoặc rỗng nếu không có job nào chờ (hoặc instance khác nhanh hơn)
     */
    Optional<Job> claim(String leaseOwner, Instant leaseUntil);

    /** Gia hạn lease và cập nhật tiến độ. {@code false} nghĩa là mất lease — handler phải dừng. */
    boolean nhipTim(long jobId, String leaseOwner, int daXong, Integer tong, Instant leaseMoi);

    void luuViTri(long jobId, Map<String, Object> viTri);

    /**
     * Đưa mọi job {@code RUNNING} có lease đã hết hạn về {@code PAUSED}.
     *
     * <p>Cùng vai trò với reaper của {@code judge_queue}: không có nó thì một lần restart để
     * lại một job {@code RUNNING} vĩnh viễn, và {@code ux_jobs_one_active_per_entity} chặn mọi
     * job cùng loại về sau — một sự cố nhỏ khoá vĩnh viễn một tính năng.
     *
     * @return số job đã thu hồi
     */
    int thuHoiJobTreo(Instant bayGio);

    void ketThuc(long jobId, JobStatus status, String errorMessage, Instant luc);

    /**
     * Đưa job về {@code PAUSED} và <b>thả lease</b> — job chưa xong, chỉ nhường lượt.
     *
     * <p>Không dùng lại {@link #ketThuc}: câu đó đặt {@code finished_at}, và một job còn phải
     * chạy tiếp mà mang dấu thời gian kết thúc là một dòng dữ liệu nói dối — nó sẽ hiện trên
     * trang theo dõi là "đã xong lúc 02:14" trong khi nó đang chờ để chạy tiếp.
     *
     * <p>{@code cursor_state} <b>không</b> bị đụng tới: nó là thứ duy nhất cho phép lần chạy
     * sau tiếp tục đúng chỗ.
     */
    void tamNghi(long jobId, String lyDo);

    /** @throws JobsException {@code job.da_ket_thuc} nếu job đã xong */
    void huy(long jobId, Instant luc);

    void ghiSuKien(long jobId, String muc, String thongDiep);

    List<JobEvent> suKienGanDay(long jobId, int gioiHan);

    record JobEvent(Instant at, String level, String message) {
    }
}
