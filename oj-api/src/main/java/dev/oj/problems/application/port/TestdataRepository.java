package dev.oj.problems.application.port;

/**
 * Ghi metadata testdata — bảng {@code testdata_versions}, {@code testcases},
 * {@code sample_testcase_contents} (V2). FR-PROB-03, 04. Bước 4.10.
 *
 * <h2>★ Mọi phương thức ở đây phải IDEMPOTENT</h2>
 * Job nạp testdata <b>sẽ</b> bị chạy lại — đó là cả điểm của Quy tắc 5 (job sống sót qua
 * restart). Một {@code INSERT} thường sẽ vỡ khoá chính ở lần chạy thứ hai và job không bao giờ
 * hoàn thành được, dù mọi thứ khác đều đúng.
 *
 * <p>Nên tất cả đều là {@code ON CONFLICT ... DO UPDATE}, và điều đó an toàn vì dữ liệu ghi
 * lần hai <b>giống hệt</b> lần một: nội dung được đánh địa chỉ bằng chính hash của nó.
 *
 * <h2>Bất biến #1 sống ở đây</h2>
 * {@link #themTestcase} chỉ nhận <b>hash và kích thước</b>, không nhận nội dung. Nội dung test
 * ẩn không có đường nào vào Postgres. {@link #themNoiDungSample} là ngoại lệ duy nhất, và nó
 * chỉ gọi được cho test đã đánh dấu {@code sample} — ràng buộc khoá ngoại tổng hợp của V2 làm
 * việc gọi nhầm trở nên <b>bất khả thi ở tầng schema</b>, không phụ thuộc việc lập trình viên
 * có nhớ viết {@code if (isSample)} hay không.
 */
public interface TestdataRepository {

    /** Số hiệu phiên bản kế tiếp của một đề. Bắt đầu từ 1. */
    int phienBanKeTiep(long problemId);

    void taoPhienBan(long problemId, int version, String manifestSha256,
                     int testCount, long totalBytes, long createdBy);

    /** @return {@code testcases.id} */
    long themTestcase(long problemId, int version, int ordinal, boolean laSample,
                      String inputSha256, String outputSha256, int inputBytes, int outputBytes);

    /**
     * FR-PROB-04 — nội dung của test <b>công khai</b>.
     *
     * <p>Gọi cho một testcase ẩn sẽ vỡ khoá ngoại tổng hợp {@code (testcase_id, is_sample)}.
     * Đó là thiết kế, không phải một tác dụng phụ.
     */
    void themNoiDungSample(long testcaseId, String input, String output);

    /**
     * Trỏ đề sang phiên bản mới — <b>bước cuối cùng</b>.
     *
     * <p>Trước lời gọi này, mọi test đã nằm đủ trong database và trong kho. Nếu job chết giữa
     * chừng thì đề vẫn dùng phiên bản cũ và không một bài nộp nào bị chấm bằng nửa bộ test —
     * đây là chỗ mà "nguyên tử" thật sự quan trọng, và nó chỉ tốn một câu {@code UPDATE}.
     */
    void kichHoatPhienBan(long problemId, int version);
}
