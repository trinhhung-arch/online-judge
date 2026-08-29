package dev.oj.platform.jobs.api;

import dev.oj.platform.jobs.Job;
import dev.oj.platform.jobs.JobRepository;
import dev.oj.platform.jobs.application.usecase.GetJobUseCase;

import java.time.Instant;
import java.util.List;

/**
 * Tiến độ của một job, dạng người dùng nhìn thấy.
 *
 * <h2>Không có {@code params}, không có {@code cursorState}, không có {@code leaseOwner}</h2>
 * Ba trường đó là chuyện nội bộ của khung job. {@code cursorState} đặc biệt: hình dạng của nó
 * do handler tự định nghĩa, nên đưa ra API là biến một chi tiết triển khai thành một hợp đồng
 * mà không ai có ý định giữ.
 *
 * @param phanTram {@code null} khi chưa biết tổng — UI hiện "đang chuẩn bị" thay vì một thanh
 *                 tiến độ đứng ở 0%, thứ trông giống hệt một job treo
 */
public record JobResponse(
        long id,
        String type,
        String status,
        Integer phanTram,
        int doneItems,
        Integer totalItems,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        String errorMessage,
        List<SuKien> suKien) {

    public record SuKien(Instant at, String level, String message) {
    }

    public static JobResponse tu(GetJobUseCase.ChiTiet chiTiet) {
        return tu(chiTiet.job(), chiTiet.suKien());
    }

    public static JobResponse tu(Job job) {
        return tu(job, List.of());
    }

    private static JobResponse tu(Job job, List<JobRepository.JobEvent> suKien) {
        return new JobResponse(
                job.id(),
                job.type().name(),
                job.status().name(),
                job.phanTram(),
                job.doneItems(),
                job.totalItems(),
                job.createdAt(),
                job.startedAt(),
                job.finishedAt(),
                job.errorMessage(),
                suKien.stream()
                        .map(e -> new SuKien(e.at(), e.level(), e.message()))
                        .toList());
    }
}
