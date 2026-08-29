package dev.oj.judging.api;

import dev.oj.judging.application.usecase.WatchSubmissionUseCase;
import dev.oj.platform.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * ★ Bước 3.9 · FR-SUB-05 — luồng verdict theo thời gian thực.
 *
 * <h2>File riêng, không nhét vào {@code SubmissionController}</h2>
 * Ba endpoint kia là request-response: vào, ra, xong. Cái này giữ một kết nối sống hàng phút,
 * có nhịp tim, có ba nhánh dọn dẹp, và hỏng theo những kiểu hoàn toàn khác. Trộn chung thì
 * mỗi lần sửa một endpoint REST bình thường lại phải đọc qua vòng đời của SSE.
 *
 * <h2>★ Sự kiện mang TRẠNG THÁI, không mang DỮ LIỆU</h2>
 * Mỗi sự kiện nói "bài này vừa đổi trạng thái", kèm đủ để vẽ thanh tiến độ. Nó <b>không</b>
 * mang số thứ tự test sai, không mang verdict từng test, không mang log compiler. Muốn chi
 * tiết thì client gọi {@code GET /api/v1/submissions/{id}} — nơi bộ lọc theo
 * {@code problems.feedback_level} đã có sẵn và <b>chỉ có một chỗ để sai</b>.
 *
 * <p>Đây không phải dè dặt thừa: {@code FeedbackLevel} ghi rõ luồng SSE là chỗ thứ hai dễ
 * quên bộ lọc nhất, sau trang chi tiết. Cách chắc chắn nhất để không quên một bộ lọc là
 * không có gì để lọc (bất biến #1, SEC3).
 *
 * <h2>★ Bước 3.10 — fallback REST là BẮT BUỘC, không phải tuỳ chọn</h2>
 * Kết nối này <b>sẽ</b> đứt: Cloudflare Tunnel có trần thời gian sống, và
 * {@code oj.sse.timeout} cố tình đặt thấp hơn trần đó để ta chủ động đóng trước — chủ động
 * thì client biết đường mở lại, bị động thì nó ngồi nhìn một kết nối đã chết. Client phải:
 *
 * <pre>
 *   onerror / onclose  →  GET /api/v1/submissions/{id}     đồng bộ lại NGAY
 *                      →  mở lại luồng nếu bài chưa DONE
 * </pre>
 *
 * Không có nhánh đó thì tính năng chưa xong ({@code oj-api/CLAUDE.md} mục 4), và triệu chứng
 * là "thỉnh thoảng trang đứng im mãi ở đang chấm" — thứ không ai tái hiện được.
 *
 * <p>Thiết kế trên làm việc đồng bộ lại rẻ: sự kiện <b>đầu tiên</b> của mỗi kết nối luôn là
 * trạng thái hiện tại, nên mở lại luồng tự nó vá mọi khoảng trống.
 *
 * <h2>Virtual threads</h2>
 * Việc đẩy sự kiện chạy trên luồng ảo của {@code RedisMessageListenerContainer}
 * ({@code RedisEventBusConfig}), không phải trên luồng phục vụ request. 1000 kết nối SSE với
 * luồng nền tảng là không khả thi ({@code oj-api/CLAUDE.md} mục 4).
 */
@RestController
@RequestMapping("/api/v1/submissions")
public class SubmissionSseController {

    private static final Logger log = LoggerFactory.getLogger(SubmissionSseController.class);

    private final WatchSubmissionUseCase watchSubmission;
    private final SseHeartbeat heartbeat;
    private final AppProperties properties;

    public SubmissionSseController(WatchSubmissionUseCase watchSubmission,
                                   SseHeartbeat heartbeat,
                                   AppProperties properties) {
        this.watchSubmission = watchSubmission;
        this.heartbeat = heartbeat;
        this.properties = properties;
    }

    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable long id) {
        SseEmitter emitter = new SseEmitter(properties.sse().timeout().toMillis());

        // Quyền kiểm TRONG use-case, trước khi đăng ký nghe (bất biến #11). Bài của người
        // khác ném NotFound ở đây — trước khi có bất kỳ kết nối nào được mở, và trước khi
        // có bất kỳ byte nào được gửi đi.
        WatchSubmissionUseCase.Watch watch = watchSubmission.start(id, event -> {
            SseEmitters.send(emitter, event);
            if (event.isTerminal()) {
                emitter.complete();
            }
        });

        SseEmitters.send(emitter, watch.current());

        // Trường hợp phổ biến nhất: người ta mở lại link một bài nộp cũ. Đừng giữ một kết
        // nối 5 phút để chờ một sự kiện sẽ không bao giờ tới.
        if (watch.alreadyFinished()) {
            emitter.complete();
            return emitter;
        }

        AutoCloseable ping = heartbeat.start(emitter);
        // Cả BA nhánh đều phải dọn: hết hạn, client đóng, và lỗi. Thiếu một nhánh là rò rỉ
        // một listener Redis cộng một tác vụ nhịp tim cho mỗi lần người dùng bấm F5 — và
        // trang bài nộp là trang người ta bấm F5 nhiều nhất.
        Runnable cleanup = () -> {
            closeQuietly(ping);
            watch.close();
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(() -> {
            cleanup.run();
            emitter.complete();
        });
        emitter.onError(error -> cleanup.run());
        return emitter;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception e) {
            log.warn("Không dọn được tài nguyên của một luồng SSE: {}", e.toString());
        }
    }
}
