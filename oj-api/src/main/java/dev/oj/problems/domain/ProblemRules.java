package dev.oj.problems.domain;

import dev.oj.contract.CheckerType;

import java.math.BigDecimal;

/**
 * Kiểm đầu vào khi soạn đề — FR-PROB-01. Java thuần.
 *
 * <h2>Mỗi ràng buộc ở đây có một bản sao trong CHECK của V2, và đó là chủ ý</h2>
 * Bản ở đây cho ra câu tiếng Việt người soạn đề đọc được; bản trong database bảo đảm rằng một
 * đường ghi nào đó quên gọi hàm này vẫn không tạo được dữ liệu sai. Sửa một bên thì sửa cả hai.
 *
 * <p>Các con số trùng đúng V2: thời gian 100–30000ms, bộ nhớ 16384–1048576KB.
 */
public final class ProblemRules {

    public static final int TIME_LIMIT_MIN_MS = 100;
    public static final int TIME_LIMIT_MAX_MS = 30_000;
    public static final int MEMORY_LIMIT_MIN_KB = 16_384;
    public static final int MEMORY_LIMIT_MAX_KB = 1_048_576;
    public static final int TITLE_MAX = 200;

    /**
     * Đề dài nhất chấp nhận được, tính bằng ký tự.
     *
     * <p>Cột là {@code TEXT} nên database nhận cả một megabyte. Trần này tồn tại vì đề được
     * render <b>server-side</b> (FR-PROB-02): một đề 50MB là một lần phân tích Markdown 50MB
     * trong cùng tiến trình đang giữ đường nộp bài. Không phải giới hạn về lưu trữ, mà là
     * giới hạn về thời gian CPU của một request.
     */
    public static final int STATEMENT_MAX_CHARS = 200_000;

    private ProblemRules() {
    }

    public static void kiemTraMaDe(String code) {
        if (code == null || !Problem.isValidCode(code)) {
            throw ProblemsException.khongHopLe("problem.ma_khong_hop_le",
                    "Mã đề phải dài 2–32 ký tự và chỉ gồm chữ, số, gạch dưới hoặc gạch ngang.");
        }
    }

    public static void kiemTraTieuDe(String title) {
        if (title == null || title.isBlank() || title.length() > TITLE_MAX) {
            throw ProblemsException.khongHopLe("problem.tieu_de_khong_hop_le",
                    "Tiêu đề phải có từ 1 đến " + TITLE_MAX + " ký tự.");
        }
    }

    public static void kiemTraDeBai(String statementMd) {
        if (statementMd == null || statementMd.isBlank()) {
            throw ProblemsException.khongHopLe("problem.de_bai_rong",
                    "Nội dung đề không được để trống.");
        }
        if (statementMd.length() > STATEMENT_MAX_CHARS) {
            throw ProblemsException.khongHopLe("problem.de_bai_qua_dai",
                    "Nội dung đề quá dài (tối đa " + STATEMENT_MAX_CHARS + " ký tự).");
        }
    }

    public static void kiemTraGioiHan(int timeLimitMs, int memoryLimitKb) {
        if (timeLimitMs < TIME_LIMIT_MIN_MS || timeLimitMs > TIME_LIMIT_MAX_MS) {
            throw ProblemsException.khongHopLe("problem.gioi_han_thoi_gian",
                    "Giới hạn thời gian phải từ " + TIME_LIMIT_MIN_MS + "ms đến "
                            + TIME_LIMIT_MAX_MS + "ms.");
        }
        if (memoryLimitKb < MEMORY_LIMIT_MIN_KB || memoryLimitKb > MEMORY_LIMIT_MAX_KB) {
            throw ProblemsException.khongHopLe("problem.gioi_han_bo_nho",
                    "Giới hạn bộ nhớ phải từ " + (MEMORY_LIMIT_MIN_KB / 1024) + "MB đến "
                            + (MEMORY_LIMIT_MAX_KB / 1024) + "MB.");
        }
    }

    /**
     * {@code ck_problems_epsilon} của V2: epsilon <b>có mặt khi và chỉ khi</b> checker là
     * {@code float}.
     *
     * <p>Ràng buộc hai chiều chứ không chỉ "float thì phải có epsilon": một epsilon sót lại
     * trên đề dùng checker {@code token} là một con số không ai đọc nhưng ai cũng tưởng có
     * tác dụng — và ngày đổi checker sang float thì nó bỗng có hiệu lực với một giá trị không
     * ai chọn.
     */
    public static void kiemTraChecker(CheckerType checkerType, BigDecimal epsilon) {
        boolean laFloat = checkerType == CheckerType.FLOAT;
        if (laFloat && epsilon == null) {
            throw ProblemsException.khongHopLe("problem.thieu_epsilon",
                    "Checker so sánh số thực cần một sai số epsilon.");
        }
        if (!laFloat && epsilon != null) {
            throw ProblemsException.khongHopLe("problem.epsilon_thua",
                    "Chỉ checker so sánh số thực mới dùng epsilon.");
        }
        if (laFloat && epsilon.signum() <= 0) {
            throw ProblemsException.khongHopLe("problem.epsilon_khong_duong",
                    "Sai số epsilon phải là một số dương.");
        }
    }
}
