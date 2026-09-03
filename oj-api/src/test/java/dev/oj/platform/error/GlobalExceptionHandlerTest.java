package dev.oj.platform.error;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * ★ Client ngắt kết nối SSE không được rơi xuống lưới cuối.
 *
 * <h2>Ca này canh một lỗi đã quan sát được trên hệ thống đang chạy</h2>
 * Rời trang chi tiết bài nộp làm socket đứt. Nhịp heartbeat kế tiếp gọi {@code complete()},
 * thứ đẩy một async dispatch trở lại Tomcat, nơi Spring dựng lại
 * {@link AsyncRequestNotUsableException}. Trước bản sửa, nó rơi xuống
 * {@code handleUnexpected} và sinh ra <b>hai</b> stack trace cho một hành vi hoàn toàn bình
 * thường của người dùng:
 *
 * <pre>
 *   ERROR  Lỗi ngoài dự kiến                    ← lưới cuối
 *   WARN   No converter for ApiError with
 *          preset Content-Type text/event-stream ← hệ quả của việc cố trả JSON
 * </pre>
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("★ handler cho client ngắt kết nối trả về void — không cố ghi thân response")
    void handler_ngat_ket_noi_khong_tra_than() throws NoSuchMethodException {
        Method m = GlobalExceptionHandler.class
                .getMethod("handleClientDaNgatKetNoi", AsyncRequestNotUsableException.class);

        // ★ Đây là khẳng định quan trọng nhất của cả file.
        //
        // Response đã mang Content-Type: text/event-stream trước khi ngoại lệ xảy ra. Trả về
        // một ResponseEntity<ApiError> là yêu cầu Spring tuần tự hoá JSON vào chính response
        // ấy — không có converter nào làm được, và đó CHÍNH LÀ stack trace thứ hai trong log.
        //
        // Một lần "dọn dẹp" tương lai đổi void thành ResponseEntity cho "nhất quán với các
        // handler khác" sẽ mang lỗi ấy trở lại mà không ai nhận ra, vì nó chỉ hiện trong log.
        assertThat(m.getReturnType())
                .as("phải là void: client đã đi rồi, không có ai nhận thân response")
                .isEqualTo(void.class);

        assertThat(m.getAnnotation(ExceptionHandler.class).value())
                .containsExactly(AsyncRequestNotUsableException.class);
    }

    @Test
    @DisplayName("nuốt gọn, không ném tiếp")
    void khong_nem_tiep() {
        assertThatCode(() -> handler.handleClientDaNgatKetNoi(
                new AsyncRequestNotUsableException("client đã đóng")))
                .doesNotThrowAnyException();
    }

    /**
     * Lưới cuối vẫn phải là lưới cuối: nó bắt {@code Exception}, và Spring chọn handler
     * <b>cụ thể nhất</b> — nên thêm handler ở trên không làm mất đường xử lý lỗi thật.
     */
    @Test
    @DisplayName("lưới cuối vẫn còn nguyên, và vẫn trả 500 kèm traceId")
    void luoi_cuoi_van_con() {
        boolean coLuoiCuoi = Arrays.stream(GlobalExceptionHandler.class.getMethods())
                .map(m -> m.getAnnotation(ExceptionHandler.class))
                .filter(a -> a != null)
                .anyMatch(a -> Arrays.asList(a.value()).contains(Exception.class));

        assertThat(coLuoiCuoi)
                .as("xoá lưới cuối là để mọi lỗi chưa lường trước lọt ra ngoài dưới dạng "
                        + "trang lỗi mặc định của Tomcat, kèm chi tiết nội bộ")
                .isTrue();

        assertThat(handler.handleUnexpected(new IllegalStateException("hỏng thật"))
                .getStatusCode().value())
                .isEqualTo(500);
    }
}
