package dev.oj.platform.jobs;

import dev.oj.platform.error.DomainException;

public class JobsException extends DomainException {

    private JobsException(Kind kind, String code, String publicMessage, String logMessage) {
        super(kind, code, publicMessage, logMessage);
    }

    public static JobsException khongTimThay() {
        return new JobsException(Kind.NOT_FOUND, "job.khong_tim_thay",
                "Không tìm thấy công việc này.",
                "Job không tồn tại, hoặc người gọi không được phép thấy nó");
    }

    public static JobsException dangCoJobCungLoai(JobType type) {
        return new JobsException(Kind.CONFLICT, "job.dang_chay",
                "Đã có một công việc cùng loại đang chạy cho đối tượng này. "
                        + "Chờ nó xong rồi thử lại.",
                "Chạm ux_jobs_one_active_per_entity cho type=" + type);
    }

    public static JobsException daKetThuc() {
        return new JobsException(Kind.CONFLICT, "job.da_ket_thuc",
                "Công việc này đã kết thúc, không huỷ được nữa.",
                "Yêu cầu huỷ một job đã ở trạng thái kết thúc");
    }

    public static JobsException daBiHuy() {
        return new JobsException(Kind.CONFLICT, "job.da_bi_huy",
                "Công việc đã bị huỷ.", "Handler dừng vì job bị huỷ");
    }

    public static JobsException tamNghi(String lyDo) {
        return new JobsException(Kind.CONFLICT, "job.tam_nghi", lyDo,
                "job tự nhường lượt: " + lyDo);
    }
}
