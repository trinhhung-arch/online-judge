package dev.oj.judging.application;

import dev.oj.judging.application.port.JudgeJobPublisher;
import dev.oj.judging.application.port.RejudgeRepository;
import dev.oj.judging.domain.RejudgeJob;
import dev.oj.platform.config.AppProperties;
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

/**
 * ★ FR-ADM-01 — chấm lại hàng loạt, Bước 6.3.
 *
 * <h2>Ba chốt, ba thời điểm khác nhau — và cả ba đều cần thiết</h2>
 * <ol>
 *   <li><b>Lúc tạo job</b> ({@code StartRejudgeUseCase}) — không kỳ thi nào đang chạy.</li>
 *   <li><b>Mỗi lô</b>, ở đây — kiểm lại, vì một job chạy 30 phút có thể vắt qua giờ khai mạc
 *       của một kỳ thi được lên lịch từ trước. Chốt lúc tạo không nói gì về phút thứ 25.</li>
 *   <li><b>Mỗi lô</b>, cũng ở đây — cái phanh của {@link RejudgeJob}, đo hàng đợi thật.</li>
 * </ol>
 *
 * <p>Chốt (2) là chốt dễ quên nhất và cũng là chốt cứu contest. Bỏ nó đi thì hệ thống vẫn
 * "đúng" theo mọi test viết cho chốt (1).
 *
 * <h2>Dừng thế nào cho đúng: nhường lượt, không kết thúc</h2>
 * Khi phanh ăn hoặc kỳ thi khai mạc, job <b>chưa xong</b>. Kết thúc nó là mất phần còn lại;
 * ngủ trong luồng là giữ lease và nói dối trạng thái. {@link JobsException#tamNghi} đưa job về
 * {@code PAUSED} và thả lease — nhịp {@code JobRunner} kế tiếp nhặt lại từ {@code cursor_state}.
 *
 * <h2>Idempotent theo {@code cursor_state} — hợp đồng của {@link JobHandler}</h2>
 * Vị trí là một con số: {@code lastSubmissionId}. Chạy lại từ đó không đẩy trùng bài nào, và
 * kể cả nếu có thì {@code ON CONFLICT DO NOTHING} của {@code dayVaoHangDoi} vẫn giữ đúng —
 * hai lớp cho cùng một bảo đảm, vì lớp thứ nhất là con số trong JSONB mà một lần restart
 * không đúng lúc có thể làm cũ đi.
 */
@Component
public class RejudgeJobHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(RejudgeJobHandler.class);

    /** Khoá trong {@code jobs.params} — đặt bởi {@code StartRejudgeUseCase}. */
    public static final String THAM_SO_DE = "problemId";

    /** Khoá trong {@code jobs.cursor_state}. */
    static final String VI_TRI = "lastSubmissionId";

    /**
     * ★ Số bài đã đẩy, cũng nằm trong {@code cursor_state} — và nó phải nằm ở đó.
     *
     * <h2>Vì sao cần khoá thứ hai chứ không chỉ vị trí</h2>
     * Job này <b>tạm nghỉ liên tục theo thiết kế</b>: mỗi lô đẩy nhiều nhất
     * {@code max-in-flight} bài rồi tự phanh cho tới khi máy chấm rút bớt. Nghĩa là một lượt
     * chấm lại 5000 bài chạy qua hàng nghìn lần {@code PAUSED → RUNNING}.
     *
     * <p>Bản đầu chỉ lưu {@link #VI_TRI} và để bộ đếm là biến cục bộ. Vị trí thì khôi phục
     * đúng, nên <i>công việc</i> vẫn chạy đúng và vẫn xong đủ — nhưng bộ đếm về 0 sau mỗi lần
     * nghỉ, nên {@code done_items} luôn hiện đúng số bài của <b>lượt chạy hiện tại</b>. Đo
     * được trên hệ thống đang chạy: job chấm lại 34 bài đứng nguyên ở {@code 2/34} suốt thời
     * gian chạy rồi nhảy thẳng lên {@code 34/34} lúc kết thúc.
     *
     * <p>FR-ADM-01 đòi tiến độ, và một thanh tiến độ đứng im ở 2/34 nói sai đúng cái điều mà
     * người vận hành cần biết: nó bảo job đang kẹt trong khi job đang chạy tốt. Chính javadoc
     * của {@code ctx.tienDo(0, tong)} phía dưới đã viết ra nguy cơ ấy cho {@code totalItems};
     * {@code doneItems} mắc đúng lỗi đó ở một chỗ khác.
     */
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

        AppProperties.Rejudge cauHinh = properties.judge().rejudge();
        int tong = rejudge.demBaiCuaDe(problemId);
        // Khôi phục CẢ HAI, không chỉ vị trí — xem javadoc DA_XONG.
        int daXong = ctx.viTriDaLuu().containsKey(DA_XONG)
                ? (int) soNguyen(ctx.viTriDaLuu(), DA_XONG) : 0;

        // Báo tổng NGAY, trước vòng lặp. Không có dòng này thì một job bị phanh ở lô đầu tiên
        // hiện `totalItems: null` trên trang theo dõi — người vận hành nhìn thấy một job
        // "đang dừng" mà không biết nó dừng ở đâu trong bao nhiêu. FR-ADM-01 đòi tiến độ, và
        // "chưa biết tổng" chỉ đúng khi thật sự chưa biết; ở đây ta đã đếm xong rồi.
        ctx.tienDo(Math.min(daXong, tong), tong);

        while (true) {
            ctx.kiemHuy();
            kiemChotTungLo(ctx);

            // ★ Hai lý do dừng khác nhau, và người vận hành lúc 2 giờ sáng cần phân biệt được.
            //
            // suatConLai() trả 0 cho cả hai, nên nếu chỉ đọc con số ấy thì thông điệp phải
            // gộp — và một thông điệp gộp nói "hàng đợi đang bận" trong lúc hàng đợi trống
            // là thứ làm người ta mất mười lăm phút đi tìm một sự cố không có.
            //
            //   phanh:  hàng đợi LIVE đang chờ lâu -> có sự cố thật, hoặc đang cao điểm
            //   đủ trần: mọi thứ bình thường, chỉ là đang đi đúng tốc độ đã định
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

            // Gõ cửa SAU khi hàng đã commit — cùng luật với đường nộp bài. Chuông hỏng thì
            // bỏ qua: bài đã nằm trong judge_queue, worker thấy nó ở nhịp poll kế tiếp.
            for (Long id : vao) {
                goCuaImLang(id);
            }

            ctx.luuViTri(Map.of(VI_TRI, viTri, DA_XONG, daXong));
            ctx.tienDo(Math.min(daXong, tong), tong);
            log.debug("Rejudge đề {}: đẩy {}/{} bài, tới id {}", problemId, vao.size(),
                    lo.size(), viTri);
        }
    }

    /**
     * Chốt (2) và phanh tay {@code system_settings}. Kiểm mỗi lô, không phải mỗi bài: một
     * truy vấn thêm cho mỗi 200 bài là không đáng kể, còn một truy vấn cho mỗi bài thì job
     * này tự trở thành tải.
     */
    private void kiemChotTungLo(JobContext ctx) {
        if (!congTac.bat(SystemSettings.REJUDGE, true)) {
            ctx.ghiSuKien("WARN", "Công tắc rejudge đã bị tắt — dừng lại.");
            throw JobsException.tamNghi("Chấm lại hàng loạt đang bị tắt trên toàn hệ thống.");
        }
        if (lichThi.coKyThiDangChay()) {
            // Kỳ thi khai mạc GIỮA lúc job đang chạy. Chốt lúc tạo job không nói gì về
            // phút thứ 25, và đây là chỗ duy nhất bắt được.
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
