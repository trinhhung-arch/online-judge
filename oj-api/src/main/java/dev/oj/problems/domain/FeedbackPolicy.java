package dev.oj.problems.domain;

/**
 * ★ Bước 3.11 · FR-PROB-07 — <b>một chỗ duy nhất</b> quyết định tác giả bài nộp được thấy gì.
 *
 * <h2>Vì sao là một type chứ không phải một câu {@code if} ở chỗ dựng DTO</h2>
 * Vì có <b>hai</b> đường dữ liệu đi tới người dùng — trang chi tiết và luồng SSE — và
 * {@link FeedbackLevel} đã ghi sẵn rằng cả hai đều dễ quên bộ lọc. Một câu {@code if} thì
 * phải nhớ viết hai lần; một type thì chỗ nào cần cũng phải đi qua nó, và ngày thêm đường
 * thứ ba (M4 có endpoint cho SETTER) thì câu hỏi "lọc chưa" trả lời được bằng cách nhìn chữ ký.
 *
 * <p>Ở dự án này luồng SSE giải quyết vấn đề bằng cách <b>không mang gì để lọc</b>
 * ({@code SubmissionEventBus}), nên trên thực tế chỉ còn một chỗ gọi. Type này vẫn tồn tại,
 * vì cái giữ được bất biến không phải là số chỗ gọi hôm nay mà là chỗ đặt câu trả lời.
 *
 * <h2>Điều nó KHÔNG quyết định</h2>
 * <ul>
 *   <li><b>Log compiler.</b> Ma trận hiển thị ({@code oj-api/CLAUDE.md} mục 2) cho tác giả xem
 *       vô điều kiện — đó là output từ chính mã của họ, không phải dữ liệu của đề. Một đề đặt
 *       mức {@code NONE} vẫn phải nói được vì sao bài không biên dịch nổi, nếu không thì
 *       {@code CE} thành một bức tường.</li>
 *   <li><b>Nội dung testcase.</b> Không mức nào cho xem, kể cả {@code SAMPLE_DETAIL} — cái đó
 *       chỉ mở nội dung test <i>mẫu</i>, thứ vốn đã công khai trong đề bài. Bất biến #1 không
 *       có ngoại lệ và không đi qua class này.</li>
 * </ul>
 */
public record FeedbackPolicy(FeedbackLevel level) {

    public FeedbackPolicy {
        if (level == null) {
            throw new NullPointerException("level — không có mặc định ngầm cho một bộ lọc");
        }
    }

    public static FeedbackPolicy of(FeedbackLevel level) {
        return new FeedbackPolicy(level);
    }

    /**
     * Số thứ tự test sai, hoặc {@code null} nếu đề không công bố.
     *
     * <p>Bài nộp <b>luôn lưu</b> con số này ({@code submissions.failed_test_ordinal}) — cần
     * cho SETTER, cho ADMIN, và cho việc đối chiếu khi verdict đổi. Việc người nộp có được
     * thấy nó hay không là một quyết định của <i>đề</i>, và nó được đưa ra ở đây, ngay trước
     * khi dữ liệu rời khỏi hệ thống.
     *
     * <p>Với thể thức ICPC ({@code NONE}), biết mình sai là đủ. Biết sai ở test nào là một
     * kênh thông tin: nộp mười bài cố tình khác nhau rồi đọc số thứ tự test sai là dò được
     * hình dạng của bộ test — chậm hơn nhưng cùng bản chất với việc đọc thẳng nội dung test.
     */
    public Integer failedTestOrdinal(Integer actual) {
        return level.revealsFailedTestOrdinal() ? actual : null;
    }

    /** Test mẫu được xem nội dung — dùng ở M4, trang đề bài. */
    public boolean revealsSampleDetail() {
        return level.revealsSampleDetail();
    }
}
