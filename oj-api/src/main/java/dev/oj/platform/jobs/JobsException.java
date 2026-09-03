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
     * Chạm {@code ux_jobs_one_active_per_entity} (V9) — mỗi <b>(loại, thực thể)</b> chỉ được
     * có tối đa một job đang sống.
     *
     * <p>Đây là hàng rào chống <b>một cú double click</b> trên trang admin. V6 khoá theo
     * <i>loại</i>, và nó chặn rộng hơn ý định: hai SETTER nạp testdata cho hai đề khác nhau
     * cũng đụng nhau. V9 thu phạm vi về đúng thứ cần chặn — cùng đề, cùng loại.
     *
     * <p>Thứ V6 thật sự sợ — "ba job rejudge cùng bơm vào {@code judge_queue}" — giờ do
     * {@code RejudgeJob.suatConLai} chặn, và nó đếm toàn bộ số dòng {@code priority = 10}
     * đang chờ chứ không đếm theo job. N job rejudge chia nhau một ngân sách 30%: chậm hơn,
     * không đông hơn.
     */
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

    /**
     * Job tự nhường lượt: nó chưa xong, nhưng lúc này chưa phải lúc chạy tiếp.
     *
     * <h2>Vì sao nhường lượt chứ không {@code Thread.sleep}</h2>
     * {@code RejudgeJobHandler} phải phanh khi hàng đợi bài nộp trực tiếp bắt đầu chờ lâu
     * (FR-ADM-01: <i>"tự giảm về 0 khi queue_wait live > 5s"</i>). Ngủ trong handler thì
     * lease vẫn bị giữ, tiến độ vẫn hiện RUNNING, và người vận hành nhìn vào không phân biệt
     * được "đang chạy" với "đang bị phanh".
     *
     * <p>{@code PAUSED} nói đúng sự thật, và {@code JdbcJobRepository.CLAIM} đã nhận
     * {@code PENDING} lẫn {@code PAUSED} từ đầu — nên nhịp kế tiếp tự nhặt job lên chạy tiếp
     * từ {@code cursor_state}. Đây là cùng một cơ chế đã cho job sống sót qua restart, dùng lại
     * cho một mục đích khác.
     *
     * @param lyDo hiện cho người vận hành đọc. <b>Không chứa dữ liệu nhạy cảm</b> — bảng
     *             {@code jobs} là bề mặt ADMIN đọc được (bất biến #9)
     */
    public static JobsException tamNghi(String lyDo) {
        return new JobsException(Kind.CONFLICT, "job.tam_nghi", lyDo,
                "job tự nhường lượt: " + lyDo);
    }
}
