package dev.oj.platform.error;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("★ handler cho client ngắt kết nối trả về void — không cố ghi thân response")
    void handler_ngat_ket_noi_khong_tra_than() throws NoSuchMethodException {
        Method m = GlobalExceptionHandler.class
                .getMethod("handleClientDaNgatKetNoi", AsyncRequestNotUsableException.class);

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
