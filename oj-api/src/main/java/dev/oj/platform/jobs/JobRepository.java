package dev.oj.platform.jobs;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface JobRepository {

    long tao(JobType type, Map<String, Object> params, Long createdBy);

    Optional<Job> timTheoId(long jobId);

    Optional<Job> timChoNguoiGoi(long jobId, long requesterId, boolean laAdmin);

    Optional<Job> dangSong(JobType type, String khoaThucThe);

    List<Job> ganDay(Long createdBy, Long sauId, int gioiHan);

    Optional<Job> claim(String leaseOwner, Instant leaseUntil);

    boolean nhipTim(long jobId, String leaseOwner, int daXong, Integer tong, Instant leaseMoi);

    void luuViTri(long jobId, Map<String, Object> viTri);

    int thuHoiJobTreo(Instant bayGio);

    void ketThuc(long jobId, JobStatus status, String errorMessage, Instant luc);

    void tamNghi(long jobId, String lyDo);

    void huy(long jobId, Instant luc);

    void ghiSuKien(long jobId, String muc, String thongDiep);

    List<JobEvent> suKienGanDay(long jobId, int gioiHan);

    record JobEvent(Instant at, String level, String message) {
    }
}
