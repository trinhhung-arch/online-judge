package dev.oj.judging.api.dto;

import dev.oj.judging.application.usecase.SubmitSolutionUseCase.SubmissionAccepted;

/**
 * Thân của {@code 202 Accepted} — FR-SUB-02.
 *
 * <p><b>Hai trường, và sẽ mãi là hai trường.</b> Không có verdict ở đây và sẽ không bao giờ
 * có: chữ "trả về kết quả chấm" trong một đặc tả là thứ phá năm chỉ số NFR cùng lúc
 * ({@code frplan.md} Phần 0). Client nhận {@code submissionId}, rồi theo dõi qua SSE (M3)
 * hoặc polling {@code GET /api/v1/submissions/{id}}.
 *
 * <p>Mã trạng thái là <b>202</b> chứ không phải 201: 201 nghĩa là "đã tạo xong tài nguyên",
 * còn ở đây thứ người dùng thật sự đặt hàng — một verdict — mới chỉ được nhận vào hàng đợi.
 */
public record SubmissionAcceptedResponse(long submissionId, String status) {

    public static SubmissionAcceptedResponse from(SubmissionAccepted accepted) {
        return new SubmissionAcceptedResponse(
                accepted.submissionId(), accepted.status().name());
    }
}
