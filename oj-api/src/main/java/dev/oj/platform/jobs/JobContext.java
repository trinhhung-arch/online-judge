package dev.oj.platform.jobs;

import java.util.Map;

/**
 * Thứ một {@link JobHandler} được phép làm trong lúc chạy — và <b>chỉ những thứ này</b>.
 *
 * <h2>Vì sao handler không nhận thẳng {@link JobRepository}</h2>
 * Vì handler khi đó sẽ tự đặt được trạng thái, tự gia hạn lease, tự đánh dấu {@code DONE}.
 * Mỗi khả năng ấy là một cách để hai instance cùng chạy một job mà không ai phát hiện. Vòng
 * đời thuộc về {@link JobRunner}; handler chỉ <i>báo cáo</i> và <i>ghi nhớ vị trí</i>.
 *
 * <h2>Ba việc, và mỗi việc có một lý do tồn tại</h2>
 * <ul>
 *   <li>{@link #tienDo} — để trang theo dõi không phải đoán. Đồng thời là nhịp
 *       <b>heartbeat</b>: gọi nó là gia hạn lease, nên một job đang chạy thật không bị
 *       {@code JobRunner} tưởng là chết. Handler chạy lâu mà không gọi hàm này sẽ bị nhặt lại
 *       và chạy song song với chính nó.</li>
 *   <li>{@link #luuViTri} — <b>điều kiện để Quy tắc 5 đúng</b>. Không gọi thì job vẫn chạy,
 *       nhưng một lần restart là làm lại từ đầu.</li>
 *   <li>{@link #kiemHuy} — người vận hành bấm huỷ một job đang chạy giữa contest thì nó phải
 *       dừng được. Gọi trong vòng lặp chính, không phải mỗi phần tử.</li>
 * </ul>
 */
public interface JobContext {

    long jobId();

    /** Tham số lúc tạo job, ví dụ {@code {"problemId": 42}}. */
    Map<String, Object> params();

    /**
     * Ai đã tạo job này, hoặc {@code null} nếu do hệ thống tạo.
     *
     * <p>Ở đây chứ không trong {@code params} vì nó đã là một cột thật của bảng
     * {@code jobs}. Chép nó vào tham số là tạo ra hai nguồn sự thật cho một câu hỏi mà
     * {@code audit_log} sẽ hỏi — và hai nguồn thì sớm muộn lệch nhau.
     */
    Long nguoiTao();

    /** Vị trí đã lưu của lần chạy trước. Rỗng ở lần chạy đầu. */
    Map<String, Object> viTriDaLuu();

    /**
     * Báo tiến độ <b>và</b> gia hạn lease.
     *
     * @param tong {@code null} nếu chưa biết — UI hiện "đang chuẩn bị" thay vì một thanh tiến
     *             độ nói dối
     */
    void tienDo(int daXong, Integer tong);

    /** Ghi vị trí để lần chạy sau tiếp tục được. Hình dạng do handler tự định nghĩa. */
    void luuViTri(Map<String, Object> viTri);

    /** Ghi một dòng vào {@code job_events} cho người vận hành đọc. */
    void ghiSuKien(String muc, String thongDiep);

    /** @throws JobsException {@code job.da_bi_huy} nếu người vận hành đã bấm huỷ */
    void kiemHuy();
}
