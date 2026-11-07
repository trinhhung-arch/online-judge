package dev.oj.judging.api.dto;

import dev.oj.judging.application.usecase.SubmitSolutionUseCase.SubmissionAccepted;

public record SubmissionAcceptedResponse(long submissionId, String status) {

    public static SubmissionAcceptedResponse from(SubmissionAccepted accepted) {
        return new SubmissionAcceptedResponse(
                accepted.submissionId(), accepted.status().name());
    }
}
