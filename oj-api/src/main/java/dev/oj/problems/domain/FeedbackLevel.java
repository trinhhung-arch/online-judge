package dev.oj.problems.domain;

/**
 * Mức phản hồi của một đề — FR-PROB-07.
 *
 * <h2>Đây là biện pháp chống rò rỉ testdata, không phải một tính năng</h2>
 * {@code frplan.md} Phần 6 xếp nó vào danh sách <b>tuyệt đối không cắt</b>, kể cả khi tuần 8
 * chậm tiến độ. Lý do nằm ở {@code frplan.md} mục 3.1, và nó đáng đọc nguyên văn một lần:
 *
 * <p>Cho người dùng xem nội dung test họ vừa sai nghe như lòng tốt. Nhưng nó là một
 * <b>thuật toán rút trích</b>. Nộp một chương trình cố tình in sai ở test 1 → nhận nội dung
 * test 1. Nộp tiếp chương trình đúng test 1, sai test 2 → nhận test 2. Lặp N lần là có trọn
 * bộ test. Sau đó nộp một chương trình chỉ chứa bảng tra cứu đáp án và AC tuyệt đối mọi bài.
 * Từ lúc đó, mọi con số "đã giải bao nhiêu bài" của hệ thống mất ý nghĩa.
 *
 * <h2>Không có mức thứ tư</h2>
 * Ba mức dưới đây phủ hết những gì có thể lộ ra một cách an toàn. Nếu một yêu cầu nào đó cần
 * "chỉ hiện input thôi, không hiện output" hay "hiện với đề dễ" — dừng lại và hỏi.
 * <b>Nội dung testcase ẩn không bao giờ rời khỏi worker:</b> không qua API, không qua log,
 * không qua thông báo lỗi, không qua prompt LLM (bất biến #1, SEC3).
 *
 * <p>Chú ý cả class này không có phương thức nào trả về nội dung test. Đó là cố ý: nó chỉ trả
 * lời <i>được phép hiện bao nhiêu</i>, còn <i>hiện cái gì</i> thì nguồn dữ liệu duy nhất là
 * bảng {@code sample_testcase_contents} — bảng mà theo ràng buộc khoá ngoại tổng hợp ở V2
 * <b>không thể</b> chứa một testcase ẩn.
 */
public enum FeedbackLevel {

    /**
     * Chỉ verdict. Không nói sai ở test nào.
     *
     * <p>Dùng cho contest thể thức ICPC — {@code nfrplan.md} 4.4: "verdict trả về đồng nhất,
     * không lộ 'sai ở test 3' nếu thể thức không cho".
     */
    NONE,

    /**
     * "Sai ở test 7/50" — <b>chỉ số thứ tự, không có nội dung</b>.
     *
     * <p>Mặc định của hệ thống, và là mặc định của cột {@code problems.feedback_level} trong V2.
     * Đủ để luyện tập (biết bài mình hỏng ở khoảng nào), nhưng một số thứ tự không dựng lại
     * được bộ test.
     */
    TEST_INDEX,

    /**
     * Đầy đủ input/expected/actual, <b>nhưng chỉ với testcase đã đánh dấu {@code sample}</b>.
     *
     * <p>Dùng cho bài dành cho người mới. An toàn vì test sample vốn đã công khai ngay trên
     * trang đề — hiện lại nó ở trang kết quả không lộ thêm gì.
     */
    SAMPLE_DETAIL;

    /** Mặc định khi tạo đề mới. Khớp {@code DEFAULT 'TEST_INDEX'} trong V2. */
    public static final FeedbackLevel DEFAULT = TEST_INDEX;

    /**
     * Được phép cho tác giả bài nộp biết <b>số thứ tự</b> test bị sai?
     *
     * <p>Đây là câu hỏi mà {@code judging.api} phải hỏi trước khi đặt
     * {@code failedTestOrdinal} vào response. Worker luôn gửi con số đó về; việc lọc là của API.
     */
    public boolean revealsFailedTestOrdinal() {
        return this != NONE;
    }

    /** Được phép hiện input/expected/actual của các test {@code sample}? */
    public boolean revealsSampleDetail() {
        return this == SAMPLE_DETAIL;
    }

    public static FeedbackLevel fromCode(String code) {
        for (FeedbackLevel level : values()) {
            if (level.name().equals(code)) {
                return level;
            }
        }
        throw new IllegalArgumentException("feedback_level không hợp lệ: " + code);
    }

    // -------------------------------------------------------------------------
    // Hai chỗ PHẢI gọi revealsFailedTestOrdinal(), và cả hai đều dễ quên:
    //
    //   1. Trang chi tiết bài nộp (M1, GET /submissions/{id}) — nguồn dữ liệu là
    //      submissions.failed_test_ordinal, luôn có giá trị dù mức là NONE.
    //
    //   2. Luồng SSE tiến độ (M3, JudgeProgressDto) — payload mang verdict TỪNG test.
    //      Đẩy thẳng nó ra SSE là mở lại đúng đường rò rỉ mà mức NONE sinh ra để đóng.
    //      Lọc PHẢI xảy ra trước khi publish lên Redis pub/sub, không phải ở trình duyệt.
    // -------------------------------------------------------------------------
}
