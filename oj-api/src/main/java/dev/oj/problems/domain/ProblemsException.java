package dev.oj.problems.domain;

import dev.oj.platform.error.DomainException;

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

    public static ProblemsException dangTrongKyThi() {
        return new ProblemsException(Kind.CONFLICT, "problem.dang_trong_ky_thi",
                "Đề này đang được dùng trong một kỳ thi đang diễn ra, không sửa được. "
                        + "Chờ kỳ thi kết thúc, hoặc gỡ đề khỏi kỳ thi.",
                "Từ chối sửa đề đang nằm trong contest đang chạy (FR-PROB-11)");
    }

    public static ProblemsException daCoBaiNop() {
        return new ProblemsException(Kind.CONFLICT, "problem.da_co_bai_nop",
                "Đề này đã có bài nộp nên không xoá được — xoá nó là xoá cả lịch sử của "
                        + "những người đã giải. Dùng \"Gỡ xuống\" để đề ngừng nhận bài mới.",
                "Từ chối xoá đề có submissions (bất biến: không mất bài nộp)");
    }

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

    public static ProblemsException khoTestdataHong() {
        return new ProblemsException(Kind.UNAVAILABLE, "problem.kho_testdata_hong",
                "Kho dữ liệu test hiện không dùng được. Thử lại sau.",
                "MinIO không phản hồi — chi tiết ở log, KHÔNG ra HTTP (nó mang endpoint và "
                        + "tên bucket)");
    }
}
