package dev.oj.problems.domain;

import dev.oj.contract.CheckerType;

import java.math.BigDecimal;

public final class ProblemRules {

    public static final int TIME_LIMIT_MIN_MS = 100;
    public static final int TIME_LIMIT_MAX_MS = 30_000;
    public static final int MEMORY_LIMIT_MIN_KB = 16_384;
    public static final int MEMORY_LIMIT_MAX_KB = 1_048_576;
    public static final int TITLE_MAX = 200;

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
