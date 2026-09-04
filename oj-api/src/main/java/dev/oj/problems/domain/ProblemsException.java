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
    /**
     * FR-PROB-11 — đề đang nằm trong một kỳ thi đang diễn ra.
     *
     * <p>Ở đây <b>409 chứ không 404</b>, khác với {@code ContestsException.deBiKhoa()}. Hai
     * tình huống khác nhau: bên kia là một người ngoài dò đề của kỳ thi sắp tới và không được
     * biết kỳ thi ấy tồn tại; bên này là <i>chính tác giả đề</i>, người đã biết mọi thứ về nó.
     * Giấu lý do với họ chỉ tạo ra một lỗi khó hiểu.
     */
    public static ProblemsException dangTrongKyThi() {
        return new ProblemsException(Kind.CONFLICT, "problem.dang_trong_ky_thi",
                "Đề này đang được dùng trong một kỳ thi đang diễn ra, không sửa được. "
                        + "Chờ kỳ thi kết thúc, hoặc gỡ đề khỏi kỳ thi.",
                "Từ chối sửa đề đang nằm trong contest đang chạy (FR-PROB-11)");
    }

    /**
     * ★ Không xoá được đề đã có bài nộp — và đây KHÔNG phải một giới hạn kỹ thuật.
     *
     * <p>"Không mất bài nộp" là điều thứ hai trong ba điều hệ thống này bán ({@code CLAUDE.md}
     * mục 0). Một bài nộp trỏ tới đề đã biến mất là một dòng lịch sử không đọc được nữa: người
     * nộp không biết mình đã giải bài gì, và bảng xếp hạng của kỳ thi cũ mất luôn cột ấy.
     *
     * <p>Khoá ngoại {@code submissions.problem_id} cố ý KHÔNG có {@code ON DELETE CASCADE},
     * nên database cũng sẽ từ chối. Câu này tồn tại để người dùng biết vì sao, và biết mình
     * nên làm gì thay thế.
     */
    public static ProblemsException daCoBaiNop() {
        return new ProblemsException(Kind.CONFLICT, "problem.da_co_bai_nop",
                "Đề này đã có bài nộp nên không xoá được — xoá nó là xoá cả lịch sử của "
                        + "những người đã giải. Dùng \"Gỡ xuống\" để đề ngừng nhận bài mới.",
                "Từ chối xoá đề có submissions (bất biến: không mất bài nộp)");
    }

    /** Xoá một đề đang được một kỳ thi dùng sẽ làm thủng bộ đề của kỳ thi ấy. */
    public static ProblemsException dangThuocKyThi() {
        return new ProblemsException(Kind.CONFLICT, "problem.dang_thuoc_ky_thi",
                "Đề này đang thuộc một kỳ thi. Gỡ nó khỏi kỳ thi trước, rồi mới xoá được.",
                "Từ chối xoá đề còn dòng trong contest_problems");
    }

    public static ProblemsException chuaCoTestdata() {
        return new ProblemsException(Kind.CONFLICT, "problem.chua_co_testdata",
                "Đề chưa có bộ test nào. Hãy nạp testdata trước khi xuất bản.",
                "Từ chối xuất bản đề có current_testdata_version = 0");
    }

    /**
     * ★ Kho đối tượng không dùng được — Bước 6.9, dòng MinIO của bảng degraded mode.
     *
     * <h2>503, không phải 400 — và đây là một phân loại SAI đã sửa ở M6</h2>
     * Bản M4 dùng {@code khongHopLe(...)}, tức {@code Kind.INVALID}, tức HTTP 400. Nhưng
     * <b>400 nghĩa là "yêu cầu của bạn sai, sửa rồi gửi lại"</b>, và ở đây không có gì để
     * client sửa: cùng một yêu cầu ấy sẽ thành công khi MinIO sống lại.
     *
     * <p>Phân loại sai kiểu này không vô hại. Trình duyệt, thư viện client và người vận hành
     * đều xử lý 4xx và 5xx khác nhau: 4xx thì không thử lại, 5xx thì thử lại và tính vào tỉ
     * lệ lỗi hạ tầng. Một sự cố MinIO báo cáo dưới dạng 400 sẽ <b>không xuất hiện</b> trên
     * bất kỳ biểu đồ nào theo dõi sức khoẻ hệ thống — nó trông như hàng loạt người dùng gửi
     * yêu cầu hỏng.
     */
    public static ProblemsException khoTestdataHong() {
        return new ProblemsException(Kind.UNAVAILABLE, "problem.kho_testdata_hong",
                "Kho dữ liệu test hiện không dùng được. Thử lại sau.",
                "MinIO không phản hồi — chi tiết ở log, KHÔNG ra HTTP (nó mang endpoint và "
                        + "tên bucket)");
    }
}
