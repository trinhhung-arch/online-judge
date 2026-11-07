package dev.oj.problems.domain;

import dev.oj.contract.CheckerType;
import dev.oj.contract.ScoringMode;
import dev.oj.platform.security.Role;

import java.math.BigDecimal;
import java.util.regex.Pattern;

public record Problem(
        long id,
        String code,
        String title,
        String statementMd,
        int timeLimitMs,
        int memoryLimitKb,
        int outputLimitKb,
        CheckerType checkerType,
        BigDecimal checkerEpsilon,
        ScoringMode scoringMode,
        FeedbackLevel feedbackLevel,
        ProblemStatus status,
        int currentTestdataVersion,
        long ownerId,
        boolean allowPublicSolutions) {

    public static final Pattern CODE_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{2,32}$");

    public Problem {
        if (id <= 0) {
            throw new IllegalArgumentException("id phải dương");
        }
        if (!isValidCode(code)) {
            throw new IllegalArgumentException("mã đề không hợp lệ: " + code);
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title không được rỗng");
        }
        if (timeLimitMs < 100 || timeLimitMs > 30_000) {
            throw new IllegalArgumentException("time_limit_ms ngoài [100..30000]: " + timeLimitMs);
        }
        if (memoryLimitKb < 16_384 || memoryLimitKb > 1_048_576) {
            throw new IllegalArgumentException("memory_limit_kb ngoài [16384..1048576]: " + memoryLimitKb);
        }
        if (outputLimitKb <= 0) {
            throw new IllegalArgumentException("output_limit_kb phải dương");
        }
        if (checkerType == null || scoringMode == null || feedbackLevel == null || status == null) {
            throw new NullPointerException("checkerType, scoringMode, feedbackLevel, status đều bắt buộc");
        }
        if ((checkerType == CheckerType.FLOAT) != (checkerEpsilon != null)) {
            throw new IllegalArgumentException(
                    "checker_epsilon bắt buộc có khi và chỉ khi checker_type='float'");
        }
        if (currentTestdataVersion < 0) {
            throw new IllegalArgumentException("current_testdata_version phải >= 0");
        }
    }

    public static boolean isValidCode(String code) {
        return code != null && CODE_PATTERN.matcher(code).matches();
    }

    public boolean hasTestdata() {
        return currentTestdataVersion >= 1;
    }

    public boolean acceptsSubmissions() {
        return status.acceptsSubmissions() && hasTestdata();
    }

    public boolean isVisibleTo(Long requesterId, Role role) {
        if (status.isPubliclyVisible()) {
            return true;
        }
        if (role != null && role.isAdmin()) {
            return true;
        }
        return requesterId != null && requesterId == ownerId;
    }

}
