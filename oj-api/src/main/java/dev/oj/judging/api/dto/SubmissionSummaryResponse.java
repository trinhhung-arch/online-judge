package dev.oj.judging.api.dto;

import dev.oj.judging.application.port.SubmissionRepository.SubmissionListItem;

import java.time.Instant;

/**
 * Một dòng của {@code GET /api/v1/submissions} — FR-SUB-07.
 *
 * <p>Mang {@code problemCode} và {@code problemTitle} vì truy vấn 6 đã {@code JOIN} sẵn —
 * nếu không, trang danh sách 20 dòng sẽ thành 21 câu query để đi tra tên đề, trên đúng trang
 * mà người dùng mở nhiều nhất.
 *
 * <p>Không có {@code failedTestOrdinal} — cùng lý do với {@link SubmissionDetailResponse}, và
 * ở đây còn rõ hơn: một danh sách 50 dòng kèm số thứ tự test sai là một công cụ rút trích
 * hàng loạt, không phải một trang lịch sử.
 */
public record SubmissionSummaryResponse(
        long submissionId,
        long problemId,
        String problemCode,
        String problemTitle,
        int languageId,
        String status,
        String verdict,
        Integer score,
        Integer timeMs,
        Instant createdAt) {

    public static SubmissionSummaryResponse from(SubmissionListItem item) {
        return new SubmissionSummaryResponse(
                item.id(),
                item.problemId(),
                item.problemCode(),
                item.problemTitle(),
                item.languageId(),
                item.status().name(),
                item.verdict() == null ? null : item.verdict().name(),
                item.score(),
                SubmissionDetailResponse.roundTo10ms(item.timeMs()),
                item.createdAt());
    }
}
