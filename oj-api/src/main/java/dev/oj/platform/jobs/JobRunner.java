package dev.oj.platform.jobs;

import dev.oj.platform.config.AppProperties;
import dev.oj.platform.trace.TraceIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * ★ Vòng đời của một job nền — Bước 4.7b, kéo lên từ M6 theo phương án (a) của
 * {@code build-order.md} PHẦN 6.
 *
 * <h2>Một job mỗi nhịp, không chạy song song — cố ý</h2>
 * Job nền ở hệ thống này là những việc <b>nặng</b>: nạp 200MB testdata, chấm lại hàng nghìn
 * bài. Chạy hai job cùng lúc là chia đôi pool connection {@code app} — pool mà đường nộp bài
 * đang dùng. Điều không thể thoả hiệp thứ hai của dự án là <i>không mất bài nộp</i>, và một
 * job nền làm cạn connection lúc 500 người nộp bài là cách mất bài rẻ nhất.
 *
 * <p>Nên: một nhịp lấy đúng một job. Job xếp hàng chờ, và đó là hành vi đúng.
 *
 * <h2>Nuốt mọi ngoại lệ ở tầng ngoài cùng — cùng lý do với {@code StaleJobReaper}</h2>
 * Spring <b>huỷ hẳn</b> một tác vụ {@code @Scheduled} nếu nó ném ra ngoài. Một lỗi tạm thời
 * sẽ làm toàn bộ hệ thống job chết im lặng cho tới lần deploy sau — và triệu chứng là "job
 * bấm rồi mà không chạy", thứ không ai nối được với nguyên nhân.
 *
 * <h2>Vì sao {@code leaseOwner} là một chuỗi ngẫu nhiên chứ không phải tên máy</h2>
 * Hai instance API trên cùng một máy (một cái đang deploy, một cái sắp tắt) có cùng hostname.
 * Một mã ngẫu nhiên sinh lúc khởi động phân biệt được chúng; hostname thì không, và hậu quả
 * là instance sắp tắt gia hạn lease hộ instance mới.
 */
@Component
public class JobRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    private final JobRepository jobs;
    private final Map<JobType, JobHandler> handlers = new EnumMap<>(JobType.class);
    private final AppProperties properties;
    private final Clock clock;
    private final String leaseOwner = java.util.UUID.randomUUID().toString();

    public JobRunner(JobRepository jobs, List<JobHandler> handlers,
                     AppProperties properties, Clock clock) {
        this.jobs = jobs;
        this.properties = properties;
        this.clock = clock;
        for (JobHandler handler : handlers) {
            JobHandler cu = this.handlers.put(handler.type(), handler);
            if (cu != null) {
                // Hai handler cùng loại là một lỗi cấu hình, và nó phải nổ LÚC KHỞI ĐỘNG.
                // Chọn im lặng một cái nghĩa là job chạy bằng hiện thực nào là chuyện may rủi
                // theo thứ tự quét component — cùng cái bẫy đã gặp ở DevSecurityConfig.
                throw new IllegalStateException(
                        "Hai JobHandler cùng khai type " + handler.type() + ": "
                                + cu.getClass().getName() + " và " + handler.getClass().getName());
            }
        }
        log.info("JobRunner sẵn sàng với {} handler: {}", this.handlers.size(),
                this.handlers.keySet());
    }

    @Scheduled(fixedDelayString = "${oj.jobs.poll-interval}")
    public void nhip() {
        try {
            thuHoiRoiChay();
        } catch (RuntimeException e) {
            log.error("Nhịp JobRunner hỏng — nuốt để bộ lập lịch không huỷ tác vụ", e);
        }
    }

    private void thuHoiRoiChay() {
        int daThuHoi = jobs.thuHoiJobTreo(clock.instant());
        if (daThuHoi > 0) {
            log.warn("Thu hồi {} job treo (lease hết hạn — instance chạy nó đã chết). "
                    + "Chúng sẽ chạy tiếp từ cursor_state đã lưu.", daThuHoi);
        }
        jobs.claim(leaseOwner, hanLeaseMoi()).ifPresent(this::chay);
    }

    private void chay(Job job) {
        JobHandler handler = handlers.get(job.type());
        if (handler == null) {
            // Job của một module chưa viết (REJUDGE ở M6, contest ở M5). Đánh dấu FAILED
            // thay vì để nó chiếm chỗ vĩnh viễn trong ux_jobs_one_active_per_type.
            log.error("Không có handler cho {} — job {} bị đánh FAILED", job.type(), job.id());
            jobs.ketThuc(job.id(), JobStatus.FAILED,
                    "Loại công việc này chưa được cài đặt.", clock.instant());
            return;
        }

        TraceIdFilter.set(null);   // job nền không có request, nhưng log vẫn cần một mã
        try {
            log.info("Bắt đầu job {} loại {}", job.id(), job.type());
            handler.chay(context(job));
            jobs.ketThuc(job.id(), JobStatus.DONE, null, clock.instant());
            log.info("Job {} xong", job.id());
        } catch (JobsException e) {
            ketThucTheoLoiNghiepVu(job, e);
        } catch (RuntimeException e) {
            // publicMessage của DomainException an toàn để hiện; mọi thứ khác thì không —
            // bảng jobs ADMIN đọc được, và một stack trace ở đó là bất biến #9 bị chạm.
            log.error("Job {} hỏng", job.id(), e);
            jobs.ketThuc(job.id(), JobStatus.FAILED,
                    "Công việc dừng vì một lỗi không lường trước. Xem log với traceId "
                            + TraceIdFilter.current() + ".", clock.instant());
        } finally {
            TraceIdFilter.clear();
        }
    }

    private void ketThucTheoLoiNghiepVu(Job job, JobsException e) {
        if ("job.da_bi_huy".equals(e.code())) {
            log.info("Job {} dừng vì bị huỷ", job.id());
            jobs.ketThuc(job.id(), JobStatus.CANCELLED, null, clock.instant());
            return;
        }
        log.warn("Job {} hỏng: {}", job.id(), e.getMessage());
        jobs.ketThuc(job.id(), JobStatus.FAILED, e.publicMessage(), clock.instant());
    }

    private Instant hanLeaseMoi() {
        return clock.instant().plus(properties.jobs().lease());
    }

    private JobContext context(Job job) {
        return new JobContext() {
            @Override
            public long jobId() {
                return job.id();
            }

            @Override
            public Map<String, Object> params() {
                return job.params();
            }

            @Override
            public Long nguoiTao() {
                return job.createdBy();
            }

            @Override
            public Map<String, Object> viTriDaLuu() {
                return job.cursorState();
            }

            @Override
            public void tienDo(int daXong, Integer tong) {
                if (!jobs.nhipTim(job.id(), leaseOwner, daXong, tong, hanLeaseMoi())) {
                    // Mất lease: instance khác đã nhặt job này. Dừng ngay, vì chạy tiếp là
                    // chạy song song với chính mình trên cùng một dữ liệu.
                    throw JobsException.daBiHuy();
                }
            }

            @Override
            public void luuViTri(Map<String, Object> viTri) {
                jobs.luuViTri(job.id(), viTri);
            }

            @Override
            public void ghiSuKien(String muc, String thongDiep) {
                jobs.ghiSuKien(job.id(), muc, thongDiep);
            }

            @Override
            public void kiemHuy() {
                jobs.timTheoId(job.id())
                        .filter(j -> j.status() == JobStatus.CANCELLED)
                        .ifPresent(j -> {
                            throw JobsException.daBiHuy();
                        });
            }
        };
    }
}
