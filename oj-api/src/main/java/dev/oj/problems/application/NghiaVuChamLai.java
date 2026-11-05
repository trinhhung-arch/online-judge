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

@Component
public class NghiaVuChamLai {

    private static final Logger log = LoggerFactory.getLogger(NghiaVuChamLai.class);

    private final JobRepository jobs;

    public NghiaVuChamLai(JobRepository jobs) {
        this.jobs = jobs;
    }

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
                    continue;
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
            ctx.ghiSuKien("INFO", "Job chấm lại #" + job.id() + " cho phiên bản " + version
                    + " đã có từ lần chạy trước — không tạo thêm.");
            return job.id();
        }
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
