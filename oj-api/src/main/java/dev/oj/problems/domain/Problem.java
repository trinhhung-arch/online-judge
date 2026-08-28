package dev.oj.problems.domain;

import dev.oj.contract.CheckerType;
import dev.oj.contract.ScoringMode;
import dev.oj.platform.security.Role;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * Một đề bài — FR-PROB-01. Java thuần: không Spring, không JPA, không Jackson.
 *
 * <h2>Vì sao {@code CheckerType} và {@code ScoringMode} lấy từ {@code oj-contract}</h2>
 * Cùng lý do với {@code Verdict}: {@code oj-contract} chỉ phụ thuộc JDK, nên {@code domain}
 * import nó vẫn qua cả bốn luật ArchUnit. Hai enum này là <b>từ vựng chung</b> — worker cần
 * biết checker nào để so output và có được early exit hay không, nên chúng buộc phải nằm
 * trong hợp đồng. Định nghĩa lại ở đây rồi viết mapper là nghi lễ, và nghi lễ nào cũng có ngày
 * lệch nhau.
 *
 * <p><b>Ngược lại, {@link FeedbackLevel} và {@link ProblemStatus} cố ý KHÔNG có trong
 * {@code oj-contract}</b> — và đó không phải chuyện tình cờ. Worker không được biết
 * {@code feedback_level}: nó luôn trả về số thứ tự test sai, còn <i>có cho người dùng thấy hay
 * không</i> là quyết định của API. Ranh giới đó chính là thứ giữ cho việc lọc phản hồi nằm ở
 * một chỗ duy nhất thay vì rải ra hai tiến trình.
 *
 * @param code                   {@code ^[A-Za-z0-9_-]{2,32}$}, so sánh không phân biệt hoa thường
 * @param statementMd            Markdown + LaTeX thô. M4 thay bằng bản render đã cache
 *                               ({@code rendered_statements}, FR-PROB-02) — render LaTeX mỗi
 *                               request là lãng phí thuần
 * @param timeLimitMs            giới hạn CPU <b>trên máy chấm chuẩn</b>, chưa nhân hệ số ngôn ngữ
 * @param checkerEpsilon         bắt buộc có khi và chỉ khi {@code checkerType == FLOAT}
 * @param feedbackLevel          xem {@link FeedbackLevel} — biện pháp chống rò rỉ, không phải tính năng
 * @param currentTestdataVersion phiên bản testdata mới nhất; {@code 0} nghĩa là chưa có testdata
 * @param ownerId                {@code users.id} của SETTER soạn đề
 */
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

    /** Khớp {@code CHECK (code ~ '^[A-Za-z0-9_-]{2,32}$')} ở V2. */
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
        // Cùng khoảng với CHECK ở V2. Đặt ở đây nữa để một bản ghi hỏng trong DB bị phát hiện
        // lúc đọc lên, chứ không phải lúc worker nhận một job có giới hạn vô nghĩa.
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
        // Gương của ck_problems_epsilon ở V2.
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

    /** Chưa upload testdata thì chưa chấm được bài nào. */
    public boolean hasTestdata() {
        return currentTestdataVersion >= 1;
    }

    /**
     * Nhận bài nộp mới được chưa?
     *
     * <p>Chưa tính tới contest: FR-CON-03 ("đề của contest chỉ truy cập trong khung giờ") là
     * một câu hỏi về contest chứ không về đề, và nó được hỏi ở M5 qua
     * {@code ContestWindowQuery} — xem ghi chú cuối file.
     */
    public boolean acceptsSubmissions() {
        return status.acceptsSubmissions() && hasTestdata();
    }

    /**
     * FR-PROB-08 — đề chưa xuất bản chỉ tác giả và ADMIN thấy.
     *
     * <p><b>Đây là lớp phòng thủ thứ hai, không phải lớp thứ nhất.</b> Lớp thứ nhất là câu
     * query: {@code ProblemRepository.findPublishedByCode} đơn giản không trả về đề {@code DRAFT},
     * nên một request thường không bao giờ chạm tới hàm này. Lọc sau khi đã load là mẫu sai
     * ({@code oj-api/CLAUDE.md} mục 2) — hàm này dành cho đường SETTER/ADMIN ở M4, nơi query
     * cố ý lấy cả đề chưa xuất bản.
     *
     * @param requesterId {@code null} nếu là khách chưa đăng nhập
     */
    public boolean isVisibleTo(Long requesterId, Role role) {
        if (status.isPubliclyVisible()) {
            return true;
        }
        if (role != null && role.isAdmin()) {
            return true;
        }
        return requesterId != null && requesterId == ownerId;
    }

    // -------------------------------------------------------------------------
    // Sẽ thêm ở M4/M5, đừng thêm sớm:
    //
    //   M4  tags, độ khó, allowPublicSolutions đã có sẵn (mâu thuẫn #9, mặc định TẮT),
    //       statementHtml thay statementMd sau khi có rendered_statements.
    //
    //   M5  FR-PROB-11 "cấm sửa đề đang nằm trong contest đang diễn ra".
    //       KHÔNG đặt contestId vào record này — đề không thuộc về contest, contest tham
    //       chiếu tới đề (contest_problems). Câu hỏi đó hỏi qua ContestWindowQuery đặt ở
    //       dev.oj.platform, vì luật ArchUnit 3 cấm problems -> contests.
    // -------------------------------------------------------------------------
}
