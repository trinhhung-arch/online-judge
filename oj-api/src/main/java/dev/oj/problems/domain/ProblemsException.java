package dev.oj.problems.domain;

import dev.oj.platform.error.DomainException;

/**
 * Lỗi của module {@code problems} <b>không phải</b> "không tìm thấy" — cái đó đã có
 * {@link ProblemNotFoundException}.
 *
 * <h2>Vì sao hai class chứ không một</h2>
 * Vì "không tìm thấy" ở module này mang một ý nghĩa riêng và nguy hiểm: nó cũng là câu trả lời
 * cho <i>"đề của người khác"</i> và <i>"đề chưa xuất bản"</i>. Gộp chung với các lỗi thường
 * (mã trùng, giới hạn sai) sẽ khiến người viết mã sau này dễ dùng nhầm một factory
 * {@code CONFLICT} ở chỗ đáng lẽ phải là 404 — và 404 ở đó không phải sự lịch sự, nó là biện
 * pháp chống dò đề trước giờ thi (FR-PROB-08).
 */
public class ProblemsException extends DomainException {

    private ProblemsException(Kind kind, String code, String publicMessage, String logMessage) {
        super(kind, code, publicMessage, logMessage);
    }

    public static ProblemsException maDeDaTonTai(String code) {
        return new ProblemsException(Kind.CONFLICT, "problem.ma_da_ton_tai",
                "Mã đề này đã được dùng. Hãy chọn mã khác.",
                "Chạm ux_problems_code_lower với code=" + code);
    }

    public static ProblemsException khongHopLe(String code, String publicMessage) {
        return new ProblemsException(Kind.INVALID, code, publicMessage, publicMessage);
    }

    /**
     * Không xuất bản được đề chưa có testdata.
     *
     * <p>Đây là chốt quan trọng nhất của FR-PROB-08: một đề {@code PUBLISHED} mà
     * {@code current_testdata_version = 0} sẽ nhận bài nộp và mọi bài đều IE — người dùng thấy
     * hệ thống hỏng, còn tác giả thì không biết mình quên gì.
     */
    public static ProblemsException chuaCoTestdata() {
        return new ProblemsException(Kind.CONFLICT, "problem.chua_co_testdata",
                "Đề chưa có bộ test nào. Hãy nạp testdata trước khi xuất bản.",
                "Từ chối xuất bản đề có current_testdata_version = 0");
    }
}
