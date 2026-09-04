package dev.oj.problems.api;

import dev.oj.problems.domain.Problem;

import java.math.BigDecimal;

/**
 * ★ Đề nhìn từ phía TÁC GIẢ — {@code GET /api/v1/problems/{id}/edit}, FR-PROB-01.
 *
 * <h2>Lỗi mà record này sinh ra để sửa</h2>
 * Trước đây endpoint soạn đề trả về {@link ProblemResponse} — chính cái record phục vụ trang
 * đề công khai. Nó cố ý <b>không</b> mang {@code checkerEpsilon} (lý do ghi ngay trong file
 * ấy) và cũng không mang {@code allowPublicSolutions}.
 *
 * <p>Nhưng {@code PUT /api/v1/problems/{id}} ghi đè <b>trọn bản ghi</b>: thiếu epsilon là
 * {@code null}, thiếu cờ là {@code false}. Nên vòng "mở một đề {@code float} → sửa tiêu đề →
 * Lưu" xoá sạch sai số, và đề vẫn chấm — chỉ là chấm sai từ đó. Không có thông báo nào, vì
 * về mặt kỹ thuật không có gì hỏng.
 *
 * <p><b>Luật rút ra, và nó rộng hơn một trường:</b> endpoint đọc-để-sửa phải trả về đúng tập
 * trường mà endpoint ghi sẽ đặt. Thiếu một trường ở chiều đọc là mất trường ấy ở chiều ghi.
 * Ai thêm trường vào {@link ProblemAuthoringRequest} thì thêm luôn vào đây —
 * {@code ProblemAuthoringRoundTripTest} đối chiếu hai danh sách ấy bằng phản chiếu và sẽ đỏ
 * nếu quên.
 *
 * <h2>Vì sao là một record RIÊNG, không phải thêm hai trường vào {@link ProblemResponse}</h2>
 * Vì {@code ProblemResponse} phục vụ {@code GET /api/v1/problems/{code}} — trang mà bất kỳ
 * ai cũng mở được. Thêm trường vào đó là mở rộng bề mặt công khai để sửa một lỗi của bề mặt
 * riêng tư. Cùng nguyên tắc {@code ProfileResponse} đã ghi: cần một góc nhìn khác thì viết
 * một record khác, đừng thêm một cờ {@code boolean} để một record đóng hai vai — một cờ đặt
 * sai là một lần rò rỉ.
 *
 * @param status  {@code DRAFT} / {@code PUBLISHED} / {@code RETIRED}. Có mặt vì trang soạn đề
 *                bày ra hai nút "Xuất bản" và "Gỡ xuống"; không biết trạng thái hiện tại thì
 *                nó bày cả hai và để người ta đoán
 */
public record ProblemAuthoringResponse(
        long problemId,
        String code,
        String title,
        String statementMd,
        String statementHtml,
        int timeLimitMs,
        int memoryLimitKb,
        String checkerType,
        BigDecimal checkerEpsilon,
        String scoringMode,
        String feedbackLevel,
        boolean allowPublicSolutions,
        String status) {

    public static ProblemAuthoringResponse from(Problem de, String statementHtml) {
        return new ProblemAuthoringResponse(
                de.id(),
                de.code(),
                de.title(),
                de.statementMd(),
                statementHtml,
                de.timeLimitMs(),
                de.memoryLimitKb(),
                de.checkerType().code(),
                de.checkerEpsilon(),
                de.scoringMode().name(),
                de.feedbackLevel().name(),
                de.allowPublicSolutions(),
                de.status().name());
    }
}
