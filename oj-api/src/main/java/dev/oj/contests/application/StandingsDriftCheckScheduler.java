package dev.oj.contests.application;

import dev.oj.contests.application.port.StandingsRepository;
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.jobs.JobParams;
import dev.oj.platform.jobs.JobRepository;
import dev.oj.platform.jobs.JobType;
import dev.oj.platform.jobs.JobsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/**
 * ★ Nhịp tạo việc đối soát bảng xếp hạng — FR-CON-09.
 *
 * <h2>Vì sao lớp này phải tồn tại</h2>
 * {@link StandingsDriftCheckJob} đã có từ Bước 5.10, đã đăng ký với {@code JobRunner}, và
 * javadoc của nó viết: <i>"Không có job này thì lệch không bao giờ được phát hiện."</i> Nhưng
 * <b>không chỗ nào trong mã nguồn tạo một job loại ấy</b> — cả hệ thống chỉ có ba chỗ gọi
 * {@code jobs.tao}, và cả ba đều là {@code TESTDATA_IMPORT} hoặc {@code REJUDGE}.
 *
 * <p>Nghĩa là lưới an toàn của bảng xếp hạng có đủ mắt lưới nhưng không ai kéo. Lời tiên đoán
 * trong javadoc ấy đã thành sự thật trên máy chủ thật: một dòng {@code contest_standings} nói
 * "99 bài đạt" trong một kỳ thi có một đề, sống năm ngày, và {@code standings_drift_checks}
 * rỗng. Không có gì hỏng — chỉ là không có gì nhìn.
 *
 * <h2>Chốt "vừa soát rồi thì thôi" nằm trong truy vấn, không nằm ở đây</h2>
 * Mỗi lần soát là một lần tính lại <b>toàn bộ</b> kỳ thi từ {@code submissions}. Nếu nhịp này
 * tạo việc cho mọi kỳ thi trong cửa sổ ở mỗi lần chạy thì với cửa sổ bảy ngày và nhịp mười
 * lăm phút, mỗi kỳ thi bị quét lại gần bảy trăm lần — đổ lên đúng những bảng mà đường
 * {@code nộp bài → verdict} đang dùng ({@code CLAUDE.md} mục 4, câu hỏi 4).
 *
 * <p>{@code canSoatLech} loại sẵn kỳ thi đã có bản soát muộn hơn một nhịp. Đặt chốt ấy trong
 * SQL chứ không trong bộ nhớ vì hai instance API cùng chạy nhịp này sẽ cùng thấy "chưa soát".
 *
 * <h2>Cửa sổ RỘNG hơn {@code standings-grace} rất nhiều, và đó là chủ ý</h2>
 * {@link StandingsUpdater} chỉ cần năm phút ân hạn vì nó chạy <i>cùng lúc</i> với kỳ thi.
 * Lệch thì tới muộn: đường đầu tiên trong ba đường mà {@code StandingsDriftCheckJob} liệt kê
 * là <i>một bài nộp bị rejudge, verdict đổi, nhưng id thì không</i> — và một lần rejudge có
 * thể xảy ra nhiều ngày sau khi chuông reo.
 *
 * <h2>Nó KHÔNG sửa gì</h2>
 * Nhịp này chỉ tạo việc đo. Sửa là {@code RebuildStandingsJob}, và người bấm là người tổ chức
 * — lý do đã ghi ở javadoc của {@code StandingsDriftCheckJob} và không đổi ở đây.
 */
@Component
public class StandingsDriftCheckScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(StandingsDriftCheckScheduler.class);

    private final StandingsRepository standings;
    private final JobRepository jobs;
    private final AppProperties properties;
    private final Clock clock;

    public StandingsDriftCheckScheduler(StandingsRepository standings, JobRepository jobs,
                                        AppProperties properties, Clock clock) {
        this.standings = standings;
        this.jobs = jobs;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${oj.contest.drift-check-interval}")
    public void nhip() {
        try {
            Instant bayGio = clock.instant();
            var cauHinh = properties.contest();

            for (long contestId : standings.canSoatLech(
                    bayGio,
                    bayGio.minus(cauHinh.driftCheckWindow()),
                    bayGio.minus(cauHinh.driftCheckInterval()))) {
                taoViec(contestId);
            }
        } catch (RuntimeException e) {
            // Cùng lý do đã ghi ở StandingsUpdater: Spring huỷ hẳn một tác vụ @Scheduled nếu
            // nó ném ra ngoài. Một kỳ thi có dữ liệu lạ không được phép tắt luôn cơ chế soát
            // cho MỌI kỳ thi còn lại.
            log.error("Nhịp soát lệch hỏng — nuốt để bộ lập lịch không huỷ tác vụ", e);
        }
    }

    private void taoViec(long contestId) {
        try {
            long jobId = jobs.tao(JobType.STANDINGS_DRIFT_CHECK,
                    Map.of(JobParams.CONTEST_ID, contestId), null);
            log.debug("Đã tạo việc soát lệch #{} cho contest {}", jobId, contestId);
        } catch (JobsException e) {
            // ★ `ux_jobs_one_active_per_entity` (V9) là chốt cuối, và chạm nó là chuyện BÌNH
            // THƯỜNG ở đây: lần soát trước còn đang chạy. Không log mức cao hơn debug —
            // một cảnh báo lặp lại mỗi nhịp là một cảnh báo sẽ bị lọc đi, kéo theo cả những
            // cảnh báo thật.
            log.debug("Contest {} đã có việc soát đang chạy, bỏ qua nhịp này", contestId);
        }
    }
}
