package dev.oj.worker.run.checker;

/**
 * So output của bài làm với đáp án. Ba hiện thực, khớp {@code CheckerType} trong hợp đồng.
 *
 * <h2>Vì sao interface này nhận {@code byte[]} chứ không phải {@code String}</h2>
 * Output bị cắt ở {@code outputLimitKb} (mặc định 64MB). Dựng hai {@code String} cỡ đó cho
 * mỗi test là nhân đôi bộ nhớ và trả giá cho việc giải mã UTF-8 những byte mà ta chỉ cần
 * <b>so sánh</b>. Ở đây byte là đủ, và với dữ liệu do người lạ tạo ra thì "đừng giải mã cái
 * mình không cần" cũng là một thói quen tốt.
 *
 * <h2>Điều mà không checker nào được làm</h2>
 * Không ném ra ngoài, không log nội dung, không đưa nội dung vào thông báo lỗi. Đáp án là
 * testdata ẩn; một dòng {@code "mong doi " + expected} là bất biến #1 bị phá bằng đúng một
 * chuỗi nối (SEC3).
 */
public interface Checker {

    /** {@code true} nếu output được chấp nhận. */
    boolean matches(byte[] expected, byte[] actual);
}
