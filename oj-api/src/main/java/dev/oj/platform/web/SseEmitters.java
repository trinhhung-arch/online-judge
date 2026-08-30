package dev.oj.platform.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Gửi lên một luồng SSE mà không biến việc client đóng tab thành một sự cố.
 *
 * <h2>Ở {@code platform.web} từ M5, trước đó ở {@code judging.api}</h2>
 * Hai trang có SSE ({@code oj-api/CLAUDE.md} mục 4) nằm ở hai module khác nhau: chi tiết bài
 * nộp ở {@code judging}, bảng xếp hạng ở {@code contests}. Để lớp này ở {@code judging.api}
 * buộc {@code contests} phải import package {@code api} của một module khác — chạy được (luật
 * ArchUnit 3 cho phép chiều đó) nhưng sai về ý: đây là hạ tầng HTTP, không phải nghiệp vụ
 * chấm bài.
 *
 * <h2>★ Vì sao {@code complete()} chứ không phải {@code completeWithError()}</h2>
 * Đây là bài học từ một lần chạy thật, không phải từ một cuốn sách.
 *
 * <p>Khi trình duyệt đóng kết nối, {@code emitter.send()} ném
 * {@code AsyncRequestNotUsableException} (bọc quanh {@code Broken pipe}). Gọi
 * {@code completeWithError()} lúc ấy sẽ đẩy ngoại lệ đó vào đường xử lý lỗi của Spring MVC,
 * và {@code GlobalExceptionHandler} ghi một <b>stack trace mức ERROR</b> — rồi chính nó cũng
 * hỏng, vì response đã đóng từ lâu:
 *
 * <pre>
 *   ERROR GlobalExceptionHandler : Lỗi ngoài dự kiến
 *   WARN  ExceptionHandlerExceptionResolver : Failure in @ExceptionHandler ...
 * </pre>
 *
 * <p>Nhưng <b>không có sự cố nào cả</b>: người dùng chỉ đóng tab. Trên một trang mà việc đóng
 * tab xảy ra hàng nghìn lần một buổi contest, đó là hàng nghìn stack trace ERROR giả — và hệ
 * quả thật sự không phải là log to ra, mà là <b>không ai còn đọc log ERROR nữa</b>. Lúc có sự
 * cố thật thì nó nằm lẫn trong đống ấy.
 *
 * <p>{@code complete()} đóng luồng im lặng và kích hoạt {@code onCompletion} — nơi listener
 * Redis và nhịp tim được dọn. Đúng việc cần làm, không kèm tiếng động.
 *
 * <h2>Bắt {@code Exception}, không bắt từng loại</h2>
 * Ba loại đã gặp: {@code IOException} (ghi hỏng), {@code IllegalStateException} (emitter đã
 * đóng), {@code AsyncRequestNotUsableException} (Spring, và nó là {@code RuntimeException}
 * nên một khối {@code catch (IOException | IllegalStateException)} để lọt). Danh sách đó sẽ
 * còn dài ra theo phiên bản Spring, và <b>mọi</b> phần tử của nó đều có cùng một cách xử lý.
 */
public final class SseEmitters {

    private static final Logger log = LoggerFactory.getLogger(SseEmitters.class);

    private SseEmitters() {
    }

    /** @return {@code false} nếu kết nối đã chết — bên gọi ngừng làm việc cho nó */
    public static boolean send(SseEmitter emitter, Object payload) {
        return attempt(emitter, SseEmitter.event().name("submission").data(payload));
    }

    /**
     * Nhịp giữ kết nối: một dòng comment SSE. Trình duyệt bỏ qua, proxy thấy có lưu lượng.
     */
    static boolean ping(SseEmitter emitter) {
        return attempt(emitter, SseEmitter.event().comment("ping"));
    }

    private static boolean attempt(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
            return true;
        } catch (Exception e) {
            log.debug("Luồng SSE đã đóng phía client: {}", e.toString());
            closeQuietly(emitter);
            return false;
        }
    }

    /** {@code complete()} trên một emitter đã hỏng cũng có thể ném — và cũng không sao. */
    private static void closeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // Kết nối đã chết theo mọi nghĩa. Không còn gì để làm và không ai cần biết.
        }
    }
}
