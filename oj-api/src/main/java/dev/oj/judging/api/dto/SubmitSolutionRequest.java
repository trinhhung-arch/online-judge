package dev.oj.judging.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * Thân của {@code POST /api/v1/submissions} — FR-SUB-01.
 *
 * <h2>Cố ý KHÔNG kiểm 64KB ở đây</h2>
 * Bản năng là gắn {@code @Size(max = 65536)} lên {@code source}. Đừng: con số đó sẽ thành bản
 * sao thứ năm của cùng một ngưỡng (DB · {@code JudgeJobDto} · {@code application.yml} ·
 * {@code DomainRules}), và là bản sao duy nhất không được đối chiếu lúc boot. Giới hạn được
 * kiểm ở {@code SourceBlob.of}, nơi nó ném ra một câu nói rõ cả trần lẫn kích thước thật của
 * người dùng.
 *
 * <p>Ba ràng buộc dưới đây chỉ chặn thứ mà domain không diễn đạt được đẹp hơn — một id âm hay
 * một ô trống. Chúng cho 400 kèm tên trường, và cắt được một lượt đi tới DB.
 *
 * @param source mã nguồn thô. <b>Không bao giờ log trường này</b> (bất biến #9) — vì thế
 *               record này ghi đè {@code toString()}
 */
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
