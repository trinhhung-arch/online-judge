package dev.oj.judging.domain;

import dev.oj.contract.JudgeJobDto;
import dev.oj.contract.TestcaseMetaDto;

/**
 * Hằng số nghiệp vụ của {@code judging} — những con số có <b>ý nghĩa</b>, không phải những con
 * số có thể <b>chỉnh</b>.
 *
 * <h2>Ranh giới: cái gì vào đây, cái gì vào {@code application.yml}</h2>
 * {@code CLAUDE.md} mục 7 cấm số ma thuật rải trong code và bắt mọi ngưỡng phải là thuộc tính
 * có tên trong {@code application.yml}. File này <b>không</b> mâu thuẫn với điều đó — nó chứa
 * loại con số khác:
 *
 * <ul>
 *   <li><b>Ở đây</b> — con số mà đổi nó là đổi <i>nghĩa</i>: {@code priority = 0} <i>là</i>
 *       định nghĩa của "bài nộp trực tiếp", {@code attempt = 1} <i>là</i> định nghĩa của
 *       "lần chấm đầu tiên". Không ai chỉnh chúng trong lúc vận hành, và chỉnh thì mọi câu SQL
 *       trong {@code duong_nong.sql} sai theo.</li>
 *   <li><b>{@code AppProperties}</b> — con số mà đổi nó là đổi <i>hành vi</i>: lease 120s,
 *       chu kỳ reaper, rate limit 10s, số lần retry IE, kích thước trang. Domain nhận chúng
 *       qua <b>tham số phương thức</b> (xem {@link JudgeQueueEntry#canRetryIe(int)}), không
 *       import — vì import {@code AppProperties} là import Spring, và luật ArchUnit 1 chặn.</li>
 *   <li><b>{@code system_settings}</b> — công tắc ADMIN phải bật/tắt được lúc 2 giờ sáng giữa
 *       contest mà không deploy lại.</li>
 * </ul>
 *
 * <p>Hai hằng cuối cùng cố ý <b>tham chiếu tới {@code oj-contract}</b> thay vì chép giá trị.
 * Chép nghĩa là một ngày nào đó API nhận một bài mà worker sẽ từ chối, và không ai biết cho
 * tới khi có người nộp đúng một file 64KB.
 */
public final class DomainRules {

    /**
     * Bài nộp trực tiếp của người dùng. Worker luôn hút cạn mức này trước
     * ({@code frplan.md} mâu thuẫn #2).
     */
    public static final int PRIORITY_LIVE = 0;

    /**
     * Rejudge hàng loạt — FR-ADM-01, hiện thực ở M6 (Bước 6.3).
     *
     * <p>Khai báo từ M1 vì cột {@code judge_queue.priority} đã có nghĩa này từ V3, và một
     * số {@code 10} gõ thẳng vào code ở tuần 11 sẽ không ai tìm ra được nữa.
     */
    public static final int PRIORITY_REJUDGE = 10;

    /** Chưa lần nào được giao cho worker. Khớp {@code DEFAULT 0} của cả hai bảng ở V3. */
    public static final int ATTEMPT_NONE = 0;

    /**
     * Lần chấm đầu tiên. Khớp {@code CHECK (attempt >= 1)} trên {@code judge_runs}:
     * không có bản ghi chấm nào mang {@code attempt = 0}, vì {@code attempt} tăng lên 1
     * đúng lúc claim — trước khi có bất cứ kết quả nào để ghi.
     */
    public static final int FIRST_ATTEMPT = 1;

    /**
     * 64KB — FR-SUB-01. Cùng một con số sống ở bốn nơi và cả bốn phải bằng nhau:
     * {@code CHECK (byte_size <= 65536)} trên {@code source_blobs} · hằng này ·
     * {@link JudgeJobDto#MAX_SOURCE_BYTES} · {@code oj.submission.max-source-bytes}.
     *
     * <p>Ba nơi sau đối chiếu lẫn nhau lúc boot ({@code AppProperties.Submission}), nơi đầu
     * là hàng rào cuối cùng trong DB. <b>Đổi là phải hỏi người</b> — {@code CLAUDE.md} mục 5.4.
     */
    public static final int MAX_SOURCE_BYTES = JudgeJobDto.MAX_SOURCE_BYTES;

    /**
     * 1000 test mỗi đề — FR-PROB-03. Dùng để chặn {@code failedTestOrdinal} vô nghĩa.
     *
     * <p>Đây là <b>số thứ tự</b>, không bao giờ là nội dung: cả file này lẫn cả package
     * {@code domain} không có một kiểu dữ liệu nào chứa được input hay output của testcase
     * (bất biến #1).
     */
    public static final int MAX_TEST_ORDINAL = TestcaseMetaDto.MAX_ORDINAL;

    private DomainRules() {
    }

    // -------------------------------------------------------------------------
    // KHÔNG thêm vào đây, dù rất muốn:
    //
    //   lease 120s · chu kỳ reaper · rate limit 10s · số lần retry IE · kích thước trang
    //       -> AppProperties. Cả năm đều là thứ vận hành sẽ muốn chỉnh mà không deploy lại,
    //          và cả năm đều nằm trong danh sách "đổi là phải hỏi người" (CLAUDE.md mục 5.4).
    //
    //   host_factor
    //       -> chỉ worker biết máy của nó. API không nhân hệ số này lần thứ hai
    //          (xem JudgeSpec.timeLimitOnReferenceHost).
    // -------------------------------------------------------------------------
}
