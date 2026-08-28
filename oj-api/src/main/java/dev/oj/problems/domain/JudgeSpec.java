package dev.oj.problems.domain;

import dev.oj.contract.CheckerType;
import dev.oj.contract.ScoringMode;
import dev.oj.contract.TestcaseMetaDto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Tất cả những gì cần để dựng một {@code JudgeJobDto} — và <b>không gì hơn</b>.
 *
 * <p>Đây là bề mặt công khai của {@code problems} dành cho {@code judging}. Nó cố ý không phải
 * {@link Problem}: {@code judging} không cần {@code statementMd}, không cần {@code ownerId},
 * và tuyệt đối không cần {@link FeedbackLevel} — lọc phản hồi là việc của tầng {@code api},
 * và một thứ không được truyền đi thì không thể bị truyền nhầm.
 *
 * <h2>Vì sao gắn với một {@code testdataVersion} cụ thể</h2>
 * Không phải "phiên bản mới nhất" mà là "phiên bản mà bài nộp này đã ghi lại lúc nộp"
 * ({@code submissions.testdata_version}). Sửa testdata tạo version mới chứ không ghi đè
 * (FR-PROB-10), nên khi verdict hôm nay khác hôm qua thì truy được ngay vì sao. Nếu chỗ này
 * dùng {@code current_testdata_version}, một lần rejudge sẽ âm thầm chấm bài cũ bằng bộ test
 * mới và không ai đối chiếu được nữa.
 *
 * @param manifestSha256 khoá cache testdata của worker. Testdata đổi thì hash đổi và cache
 *                       tự động miss — không cần cơ chế invalidate nào
 * @param testcases      <b>chỉ metadata</b>. Kiểu này lấy thẳng từ {@code oj-contract} vì hình
 *                       dạng ấy chính là hợp đồng, và vì nó là kiểu đã được bảo đảm không có
 *                       chỗ nào chứa nội dung test (bất biến #1)
 */
public record JudgeSpec(
        long problemId,
        int timeLimitMs,
        int memoryLimitKb,
        int outputLimitKb,
        CheckerType checkerType,
        BigDecimal checkerEpsilon,
        ScoringMode scoringMode,
        int testdataVersion,
        String manifestSha256,
        List<TestcaseMetaDto> testcases) {

    public JudgeSpec {
        if (problemId <= 0) {
            throw new IllegalArgumentException("problemId phải dương");
        }
        if (testdataVersion < 1) {
            throw new IllegalArgumentException(
                    "testdataVersion phải >= 1 — đề chưa có testdata thì không dựng được job");
        }
        testcases = testcases == null ? List.of() : List.copyOf(testcases);
        if (testcases.isEmpty()) {
            throw new IllegalArgumentException(
                    "JudgeSpec không có testcase nào cho đề " + problemId
                            + " version " + testdataVersion);
        }
    }

    /**
     * Giới hạn CPU quy về <b>máy chấm chuẩn</b>, đã tính hệ số ngôn ngữ — đây chính là con số
     * đi vào {@code JudgeJobDto.timeLimitMs}.
     *
     * <p><b>Không nhân {@code host_factor} ở đây.</b> Ranh giới trách nhiệm
     * ({@code docs/build-order.md} Bước M1-1):
     *
     * <pre>
     *   API    nhân: time_multiplier + startup_overhead_ms   (chỉ API có bảng languages)
     *   worker nhân: host_factor                             (chỉ worker biết máy của nó)
     * </pre>
     *
     * Nhân {@code host_factor} ở cả hai phía là bài Java được gấp đôi thời gian, và không ai
     * phát hiện ra cho tới khi có người thắc mắc vì sao một bài chậm rõ ràng vẫn AC. Đó cũng
     * là gốc của cuộc cãi nhau <i>"máy tao AC mà CI báo TLE"</i> ({@code nfrplan.md} 9.1).
     *
     * @param languageMultiplier {@code languages.time_multiplier}: C++ ×1, Java ×2-3, Python ×3-5
     * @param startupOverheadMs  {@code languages.startup_overhead_ms}: JVM ~100ms, cộng thẳng
     *                           vào giới hạn để bài Java không bị thiệt
     */
    public int timeLimitOnReferenceHost(BigDecimal languageMultiplier, int startupOverheadMs) {
        if (languageMultiplier == null || languageMultiplier.signum() <= 0) {
            throw new IllegalArgumentException("languageMultiplier phải dương");
        }
        long limit = BigDecimal.valueOf(timeLimitMs).multiply(languageMultiplier).longValue()
                + startupOverheadMs;
        // Giữ trong khoảng mà JudgeJobDto chấp nhận, nếu không thì lỗi lộ ra ở tận lúc dựng DTO.
        return (int) Math.min(30_000L, Math.max(100L, limit));
    }

    /** Bộ nhớ đã cộng phần hao của runtime ({@code languages.memory_overhead_kb}). */
    public int memoryLimitOnReferenceHost(int memoryOverheadKb) {
        long limit = (long) memoryLimitKb + memoryOverheadKb;
        return (int) Math.min(1_048_576L, Math.max(16_384L, limit));
    }

    /**
     * Điểm tối đa của đề — <b>API tính, worker chỉ dùng lại</b>.
     *
     * <p>{@code ALL_OR_NOTHING} thì luôn là {@value #ALL_OR_NOTHING_MAX_SCORE}: đúng hoặc sai,
     * không có ở giữa. M3 (FR-PROB-06) đổi thành tổng điểm các subtask khi
     * {@code scoringMode == SUBTASK} — và đó chính là lý do con số này phải đi từ đây chứ
     * không để worker tự nghĩ ra: worker không biết đề có mấy nhóm và mỗi nhóm bao nhiêu điểm.
     */
    public int maxScore() {
        return ALL_OR_NOTHING_MAX_SCORE;
    }

    /** Thang điểm quy ước cho chế độ đúng-hoặc-sai. */
    public static final int ALL_OR_NOTHING_MAX_SCORE = 100;

    public int testCount() {
        return testcases.size();
    }
}
