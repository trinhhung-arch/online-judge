package dev.oj.platform.jobs;

import dev.oj.platform.error.DomainException;

/** Lỗi của khung job nền. {@code CLAUDE.md} mục 7: không ném {@code RuntimeException} trần. */
public class JobsException extends DomainException {

    private JobsException(Kind kind, String code, String publicMessage, String logMessage) {
        super(kind, code, publicMessage, logMessage);
    }

    public static JobsException khongTimThay() {
        return new JobsException(Kind.NOT_FOUND, "job.khong_tim_thay",
                "Không tìm thấy công việc này.",
                "Job không tồn tại, hoặc người gọi không được phép thấy nó");
    }

    /**
     * Chạm {@code ux_jobs_one_active_per_type} — mỗi loại chỉ được có tối đa một job đang sống.
     *
     * <p>Đây là hàng rào chống thảm hoạ <i>"ba job rejudge hàng loạt chạy song song"</i>, thứ
     * mà <b>một cú double click</b> trên trang admin là đủ để tạo ra. 409 và một câu nói rõ
     * còn hơn ba job cùng ghi vào {@code judge_queue}.
     */
    public static JobsException dangCoJobCungLoai(JobType type) {
        return new JobsException(Kind.CONFLICT, "job.dang_chay",
                "Đã có một công việc cùng loại đang chạy. Chờ nó xong rồi thử lại.",
                "Chạm ux_jobs_one_active_per_type cho type=" + type);
    }

    public static JobsException daKetThuc() {
        return new JobsException(Kind.CONFLICT, "job.da_ket_thuc",
                "Công việc này đã kết thúc, không huỷ được nữa.",
                "Yêu cầu huỷ một job đã ở trạng thái kết thúc");
    }

    /**
     * Handler chủ động dừng vì job bị huỷ.
     *
     * <p>Không phải lỗi: {@link JobContext#kiemHuy()} ném nó để thoát khỏi một vòng lặp sâu mà
     * không cần mỗi tầng phải trả về một cờ. {@link JobRunner} bắt riêng và đặt trạng thái
     * {@code CANCELLED} thay vì {@code FAILED}.
     */
    public static JobsException daBiHuy() {
        return new JobsException(Kind.CONFLICT, "job.da_bi_huy",
                "Công việc đã bị huỷ.", "Handler dừng vì job bị huỷ");
    }
}
