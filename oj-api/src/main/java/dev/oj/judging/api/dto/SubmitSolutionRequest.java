package dev.oj.judging.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record SubmitSolutionRequest(
        @Positive(message = "problemId phải dương") long problemId,
        @NotBlank(message = "Phải chọn ngôn ngữ") String languageCode,
        @NotBlank(message = "Mã nguồn không được để trống") String source) {

    @Override
    public String toString() {
        return "SubmitSolutionRequest[problemId=" + problemId
                + ", languageCode=" + languageCode
                + ", sourceChars=" + (source == null ? 0 : source.length()) + "]";
    }
}
