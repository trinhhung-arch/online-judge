package dev.oj.worker.testdata;

/**
 * Không lấy được testdata — <b>verdict là {@code IE}, không phải WA</b>.
 *
 * <p>{@code oj-worker/CLAUDE.md} mục 6: "không tải được testdata → IE + log. Không đoán,
 * không chấm với dữ liệu thiếu". Chấm tiếp với 8/10 test rồi báo AC là loại lỗi không ai phát
 * hiện ra, vì kết quả trông hoàn toàn bình thường.
 */
public class TestdataUnavailableException extends RuntimeException {

    public TestdataUnavailableException(String message) {
        super(message);
    }

    public TestdataUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
