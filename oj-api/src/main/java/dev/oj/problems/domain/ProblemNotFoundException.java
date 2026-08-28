package dev.oj.problems.domain;

import dev.oj.platform.error.DomainException;

/**
 * Không tìm thấy đề — hoặc người gọi không được phép thấy nó.
 *
 * <h2>Một thông điệp cho cả hai trường hợp, và đó là điểm chính của class này</h2>
 * "Đề không tồn tại" và "đề tồn tại nhưng đang {@code DRAFT}" phải trả về <b>cùng một
 * response, cùng một mã 404, cùng một câu chữ</b>.
 *
 * <p>Nếu phân biệt hai trường hợp — 404 với cái này, 403 với cái kia — thì bất kỳ ai cũng dò
 * được danh sách mã đề đang tồn tại nhưng chưa xuất bản, chỉ bằng cách thử vài trăm chuỗi.
 * Mà đề chưa xuất bản rất thường là <b>đề của contest tuần sau</b>. Đây là kiểu rò rỉ không
 * lộ nội dung gì nhưng vẫn phá tính công bằng: biết trước đề tên gì, thuộc chủ đề nào, đã là
 * lợi thế.
 *
 * <p>Cùng nguyên tắc áp cho bài nộp của người khác: 404, không phải 403 — xem ghi chú cuối
 * {@link DomainException}.
 */
public class ProblemNotFoundException extends DomainException {

    private ProblemNotFoundException(String logMessage) {
        super(Kind.NOT_FOUND,
                "problem.not_found",
                "Không tìm thấy đề bài này.",   // câu ra client — giống hệt nhau ở mọi nhánh
                logMessage);                    // câu vào log — được phép nói thật
    }

    public static ProblemNotFoundException byCode(String code) {
        return new ProblemNotFoundException("Không có đề PUBLISHED với mã: " + code);
    }

    public static ProblemNotFoundException byId(long id) {
        return new ProblemNotFoundException("Không có đề PUBLISHED với id: " + id);
    }

    /**
     * Đề có thật và đã xuất bản, nhưng chưa có testdata nên chưa chấm được.
     *
     * <p>Vẫn là 404 với cùng câu chữ: một đề đã xuất bản mà thiếu testdata là lỗi vận hành
     * của SETTER, và người dùng không cần biết chi tiết đó — họ chỉ cần biết chưa nộp được.
     * Log thì ghi rõ để bạn sửa.
     */
    public static ProblemNotFoundException noTestdata(long id) {
        return new ProblemNotFoundException(
                "Đề id=" + id + " đã PUBLISHED nhưng current_testdata_version = 0 — "
                        + "SETTER xuất bản mà quên upload testdata");
    }
}
