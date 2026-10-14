package dev.oj.problems.application;

import dev.oj.platform.jobs.Job;
import dev.oj.platform.jobs.JobContext;
import dev.oj.platform.jobs.JobParams;
import dev.oj.platform.jobs.JobRepository;
import dev.oj.platform.jobs.JobType;
import dev.oj.platform.jobs.JobsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * ★ FR-PROB-10 — <b>bắt buộc</b> tạo job chấm lại khi testdata đổi. Bước 6.14.
 *
 * <p>Tách khỏi {@link TestdataImportJob} vì file đó vượt trần 300 dòng ({@code CLAUDE.md}
 * mục 7), và vì phần này có ba nhánh đáng kiểm riêng mà không cần dựng một gói ZIP.
 *
 * <h2>Vì sao "bắt buộc" chứ không "gợi ý"</h2>
 * Sau khi {@code kichHoatPhienBan} chạy, mọi bài nộp cũ mang một
 * {@code testdata_version} không còn là phiên bản hiện tại. Verdict của chúng vẫn đúng
 * <i>với bộ test lúc đó</i> và sai <i>với bộ test bây giờ</i>. Một bảng xếp hạng trộn hai
 * loại verdict ấy không so sánh được với chính nó — và không có gì trên màn hình nói ra
 * điều đó. Đây là loại sai lệch chỉ lộ ra khi có người khiếu nại, tức là quá muộn.
 *
 * <p>Nên việc chấm lại không được để cho ai nhớ: nó được tạo ngay tại đây, trong cùng
 * lượt chạy đã đổi dữ liệu.
 *
 * <h2>Phiên bản 1 thì không tạo — và đó không phải ngoại lệ, mà là định nghĩa</h2>
 * Một đề chưa có testdata thì {@code hasTestdata()} sai, và {@code GetProblemUseCase
 * .submittableById} từ chối nó. Không tồn tại bài nộp nào để chấm lại. Tạo một job rejudge
 * rỗng ở đây chỉ chiếm chỗ trong {@code ux_jobs_one_active_per_entity} và làm nhiễu trang
 * theo dõi.
 *
 * <h2>★ "Đã có job chấm lại" là ba tình huống, không phải một</h2>
 * {@code ux_jobs_one_active_per_entity} chỉ cho mỗi đề một job {@code REJUDGE} đang sống, nên
 * {@code tao} có thể ném {@code job.dang_chay}. Bản đầu coi mọi lần đụng là lỗi và bảo người
 * vận hành "tạo job chấm lại bằng tay" — sai ở đúng ca hay gặp nhất:
 *
 * <ol>
 *   <li><b>Job nạp bị nhặt lại sau khi chết</b> (Quy tắc 5: nó <i>sẽ</i> chạy lại). Lần chạy
 *       đầu đã tạo job chấm lại; lần sau đụng chính job đó. Nghĩa vụ đã xong — chỉ ghi INFO.
 *       Nhận ra bằng {@link JobParams#TESTDATA_VERSION}: một job mang phiên bản ≥ phiên bản
 *       vừa kích hoạt chỉ có thể được tạo <i>sau</i> lúc kích hoạt, nên nó sẽ đi qua mọi bài
 *       nộp bằng bộ test mới.</li>
 *   <li><b>Một job chấm lại có từ trước khi đổi testdata</b> — ADMIN bấm tay, hoặc lần nạp
 *       trước. Cursor của nó có thể đã đi qua một phần bài nộp, và những bài ấy đã (hoặc sẽ)
 *       được chấm bằng bộ test cũ. Đây là báo động <b>thật</b>: giữ ERROR. Job ADMIN không mang
 *       {@code testdataVersion} nên luôn rơi vào nhánh này — thà báo thừa còn hơn bỏ sót.</li>
 *   <li><b>Job kia vừa kết thúc</b> giữa lúc đụng và lúc hỏi. Thử tạo lại đúng một lần.</li>
 * </ol>
 *
 * <p>Vì sao không lưu id job chấm lại vào {@code cursor_state} của job nạp: chết sau khi
 * {@code tao} commit nhưng trước khi {@code luuViTri} kịp ghi là để lại đúng báo động giả cũ.
 * Khoá trong {@code params} được ghi <i>cùng một câu INSERT</i> với job, nên không có khe đó.
 *
 * <h2>"Cảnh báo rõ số bài bị ảnh hưởng" nằm ở đâu</h2>
 * {@code problems} không đọc được bảng {@code submissions} — luật ArchUnit 3 không có
 * chiều {@code problems → judging}, và nó không có chiều đó vì một lý do đúng. Con số ấy
 * là {@code total_items} của chính job chấm lại: {@code RejudgeJobHandler} đếm nó ở dòng
 * đầu tiên, và trang {@code GET /api/v1/jobs/{id}} hiện nó cùng thanh tiến độ.
 *
 * <h2>Không tạo được thì KHÔNG làm hỏng việc nạp</h2>
 * Testdata đã kích hoạt xong và đã commit. Ném ngoại lệ ở đây đánh dấu job
 * {@code TESTDATA_IMPORT} là {@code FAILED} trong khi nó đã thành công — người vận hành sẽ
 * nạp lại, và lần nạp lại ấy mới là thứ gây hại. Ghi ERROR và một dòng
 * {@code job_events} để nghĩa vụ chấm lại không biến mất trong im lặng.
 */
@Component
public class NghiaVuChamLai {

    private static final Logger log = LoggerFactory.getLogger(NghiaVuChamLai.class);

    private final JobRepository jobs;

    public NghiaVuChamLai(JobRepository jobs) {
        this.jobs = jobs;
    }

    /**
     * Gọi <b>sau</b> {@code kichHoatPhienBan}.
     *
     * @return {@code jobs.id} của job chấm lại phủ phiên bản này — mới tạo hoặc đã có từ lần
     *         chạy trước — hoặc {@code null} nếu không có job nào phủ được
     */
    public Long thucHien(JobContext ctx, long problemId, int version, long nguoiTao) {
        if (version <= 1) {
            return null;
        }
        Map<String, Object> thamSo = Map.of(
                JobParams.PROBLEM_ID, problemId,
                JobParams.TESTDATA_VERSION, version);
        Long createdBy = nguoiTao == 0 ? null : nguoiTao;

        for (int lan = 1; lan <= 2; lan++) {
            try {
                long rejudgeId = jobs.tao(JobType.REJUDGE, thamSo, createdBy);
                ctx.ghiSuKien("WARN", "★ Testdata đã đổi (phiên bản " + version + "). Mọi bài nộp "
                        + "trước đó được chấm bằng bộ test CŨ. Job chấm lại #" + rejudgeId
                        + " đã được tạo — số bài bị ảnh hưởng hiện ở tiến độ của job đó.");
                return rejudgeId;
            } catch (JobsException e) {
                if (!"job.dang_chay".equals(e.code())) {
                    return baoLoi(ctx, problemId, version, e.getMessage(), e);
                }
                Optional<Job> dangSong = jobs.dangSong(JobType.REJUDGE, String.valueOf(problemId));
                if (dangSong.isEmpty()) {
                    continue;   // ③ job kia vừa kết thúc — thử lại một lần
                }
                return daCoJob(ctx, problemId, version, dangSong.get(), e);
            } catch (RuntimeException e) {
                return baoLoi(ctx, problemId, version, e.getMessage(), e);
            }
        }
        return baoLoi(ctx, problemId, version,
                "đụng job chấm lại đang chạy hai lần liền mà không tra ra được nó", null);
    }

    private Long daCoJob(JobContext ctx, long problemId, int version, Job job, JobsException e) {
        if (phuPhienBan(job, version)) {
            // ① lần chạy trước của chính job nạp này đã tạo nó
            ctx.ghiSuKien("INFO", "Job chấm lại #" + job.id() + " cho phiên bản " + version
                    + " đã có từ lần chạy trước — không tạo thêm.");
            return job.id();
        }
        // ② job có từ trước khi đổi testdata
        return baoLoi(ctx, problemId, version, "đề đang có job chấm lại #" + job.id()
                + " tạo TRƯỚC khi testdata đổi — nó có thể đã đi qua một phần bài nộp bằng "
                + "bộ test cũ. Chờ job #" + job.id() + " xong", e);
    }

    private static boolean phuPhienBan(Job job, int version) {
        return job.params().get(JobParams.TESTDATA_VERSION) instanceof Number n
                && n.intValue() >= version;
    }

    private static Long baoLoi(JobContext ctx, long problemId, int version, String lyDo,
                               Exception e) {
        ctx.ghiSuKien("ERROR", "★ KHÔNG tạo được job chấm lại (" + lyDo
                + "). Testdata ĐÃ đổi, nên bài nộp cũ đang mang verdict của bộ test cũ. "
                + "Phải tạo job chấm lại bằng tay cho đề " + problemId + ".");
        log.error("FR-PROB-10: không tạo được job rejudge cho đề {} sau khi đổi "
                + "testdata sang phiên bản {}: {}", problemId, version, lyDo, e);
        return null;
    }
}
