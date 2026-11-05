package dev.oj.judging.application;

import dev.oj.judging.application.port.JudgeJobPublisher;
import dev.oj.judging.application.port.RejudgeRepository;
import dev.oj.judging.domain.RejudgeJob;
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.config.RejudgeProperties;
import dev.oj.platform.contest.ContestWindowQuery;
import dev.oj.platform.jobs.JobContext;
import dev.oj.platform.jobs.JobHandler;
import dev.oj.platform.jobs.JobType;
import dev.oj.platform.jobs.JobsException;
import dev.oj.platform.settings.SystemSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.Map;

@Component
public class RejudgeJobHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(RejudgeJobHandler.class);

    public static final String THAM_SO_DE = "problemId";

    static final String VI_TRI = "lastSubmissionId";

    static final String DA_XONG = "daXong";

    private final RejudgeRepository rejudge;
    private final JudgeJobPublisher chuong;
    private final ContestWindowQuery lichThi;
    private final SystemSettings congTac;
    private final AppProperties properties;
    private final Clock clock;

    public RejudgeJobHandler(RejudgeRepository rejudge, JudgeJobPublisher chuong,
                             ContestWindowQuery lichThi, SystemSettings congTac,
                             AppProperties properties, Clock clock) {
        this.rejudge = rejudge;
        this.chuong = chuong;
        this.lichThi = lichThi;
        this.congTac = congTac;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public JobType type() {
        return JobType.REJUDGE;
    }

    @Override
    public void chay(JobContext ctx) {
        long problemId = soNguyen(ctx.params(), THAM_SO_DE);
        long viTri = ctx.viTriDaLuu().containsKey(VI_TRI)
                ? soNguyen(ctx.viTriDaLuu(), VI_TRI) : 0L;

        RejudgeProperties cauHinh = properties.judge().rejudge();
        int tong = rejudge.demBaiCuaDe(problemId);
        int daXong = ctx.viTriDaLuu().containsKey(DA_XONG)
                ? (int) soNguyen(ctx.viTriDaLuu(), DA_XONG) : 0;

        ctx.tienDo(Math.min(daXong, tong), tong);

        while (true) {
            ctx.kiemHuy();
            kiemChotTungLo(ctx);

            RejudgeJob.NhipHangDoi nhip = rejudge.doNhip();
            if (nhip.liveDangChoLauHon(cauHinh.liveWaitBrake(), clock.instant())) {
                ctx.luuViTri(Map.of(VI_TRI, viTri, DA_XONG, daXong));
                throw JobsException.tamNghi("Bài nộp trực tiếp đang phải chờ quá "
                        + cauHinh.liveWaitBrake().toSeconds() + " giây — nhường chỗ cho họ. "
                        + "Chấm lại sẽ tự chạy tiếp khi hàng đợi thông.");
            }
            int suat = RejudgeJob.suatConLai(nhip, cauHinh.maxInFlight(),
                    cauHinh.liveWaitBrake(), clock.instant());
            if (suat == 0) {
                ctx.luuViTri(Map.of(VI_TRI, viTri, DA_XONG, daXong));
                throw JobsException.tamNghi("Đã đủ " + cauHinh.maxInFlight()
                        + " bài chấm lại đang chờ (trần 30% năng lực). Chạy tiếp khi máy "
                        + "chấm rút bớt — đây là nhịp bình thường, không phải sự cố.");
            }

            List<Long> lo = rejudge.baiCuaDe(problemId, viTri, Math.min(suat, cauHinh.batchSize()));
            if (lo.isEmpty()) {
                ctx.ghiSuKien("INFO", "Đã duyệt hết bài nộp của đề " + problemId);
                ctx.tienDo(tong, tong);
                return;
            }

            List<Long> vao = rejudge.dayVaoHangDoi(lo);
            viTri = lo.get(lo.size() - 1);
            daXong += lo.size();

            for (Long id : vao) {
                goCuaImLang(id);
            }

            ctx.luuViTri(Map.of(VI_TRI, viTri, DA_XONG, daXong));
            ctx.tienDo(Math.min(daXong, tong), tong);
            log.debug("Rejudge đề {}: đẩy {}/{} bài, tới id {}", problemId, vao.size(),
                    lo.size(), viTri);
        }
    }

    private void kiemChotTungLo(JobContext ctx) {
        if (!congTac.bat(SystemSettings.REJUDGE, true)) {
            ctx.ghiSuKien("WARN", "Công tắc rejudge đã bị tắt — dừng lại.");
            throw JobsException.tamNghi("Chấm lại hàng loạt đang bị tắt trên toàn hệ thống.");
        }
        if (lichThi.coKyThiDangChay()) {
            ctx.ghiSuKien("WARN", "Một kỳ thi vừa khai mạc — chấm lại tạm dừng tới khi thi xong.");
            throw JobsException.tamNghi("Đang có kỳ thi diễn ra — chấm lại tạm dừng.");
        }
    }

    private void goCuaImLang(long submissionId) {
        try {
            chuong.publishRejudgeEnqueued(submissionId);
        } catch (RuntimeException e) {
            log.warn("Không gõ cửa được cho submission {} — bài vẫn trong judge_queue: {}",
                    submissionId, e.toString());
        }
    }

    private static long soNguyen(Map<String, Object> nguon, String khoa) {
        Object v = nguon.get(khoa);
        if (v instanceof Number n) {
            return n.longValue();
        }
        throw new IllegalStateException("Thiếu tham số số nguyên '" + khoa + "' trong job");
    }
}
