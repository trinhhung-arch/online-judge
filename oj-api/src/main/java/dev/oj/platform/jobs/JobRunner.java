package dev.oj.platform.jobs;

import dev.oj.platform.config.AppProperties;
import dev.oj.platform.error.DomainException;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class JobRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    private final JobRepository jobs;
    private final Map<JobType, JobHandler> handlers = new EnumMap<>(JobType.class);
    private final AppProperties properties;
    private final Clock clock;
    private final String leaseOwner = java.util.UUID.randomUUID().toString();

    private final ExecutorService luong = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "oj-job-runner");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean dangChay = new AtomicBoolean(false);

    public JobRunner(JobRepository jobs, List<JobHandler> handlers,
                     AppProperties properties, Clock clock) {
        this.jobs = jobs;
        this.properties = properties;
        this.clock = clock;
        for (JobHandler handler : handlers) {
            JobHandler cu = this.handlers.put(handler.type(), handler);
            if (cu != null) {
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
        if (!dangChay.compareAndSet(false, true)) {
            return;
        }
        try {
            luong.execute(this::motLuot);
        } catch (RuntimeException e) {
            dangChay.set(false);
            log.warn("Không gửi được việc cho luồng job: {}", e.toString());
        }
    }

    private void motLuot() {
        try {
            thuHoiRoiChay();
        } catch (RuntimeException e) {
            log.error("Nhịp JobRunner hỏng — nuốt để không giết luồng job", e);
        } finally {
            dangChay.set(false);
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
            log.error("Không có handler cho {} — job {} bị đánh FAILED", job.type(), job.id());
            jobs.ketThuc(job.id(), JobStatus.FAILED,
                    "Loại công việc này chưa được cài đặt.", clock.instant());
            return;
        }

        TraceIdFilter.set(null);
        try {
            log.info("Bắt đầu job {} loại {}", job.id(), job.type());
            handler.chay(context(job));
            jobs.ketThuc(job.id(), JobStatus.DONE, null, clock.instant());
            log.info("Job {} xong", job.id());
        } catch (JobsException e) {
            ketThucTheoLoiNghiepVu(job, e);
        } catch (DomainException e) {
            log.warn("Job {} dừng vì lỗi nghiệp vụ [{}]: {}", job.id(), e.code(), e.getMessage());
            jobs.ghiSuKien(job.id(), "ERROR", e.publicMessage());
            jobs.ketThuc(job.id(), JobStatus.FAILED, e.publicMessage(), clock.instant());
        } catch (RuntimeException e) {
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
        if ("job.tam_nghi".equals(e.code())) {
            log.info("Job {} tạm nghỉ: {}", job.id(), e.publicMessage());
            jobs.tamNghi(job.id(), e.publicMessage());
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
