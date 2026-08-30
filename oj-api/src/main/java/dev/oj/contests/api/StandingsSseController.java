package dev.oj.contests.api;

import dev.oj.contests.application.StandingsEventBus;
import dev.oj.contests.application.usecase.GetStandingsUseCase;
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.web.SseEmitters;
import dev.oj.platform.web.SseHeartbeat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * ★ Bảng xếp hạng realtime — FR-CON-04. Trang SSE <b>thứ hai và cuối cùng</b> của hệ thống
 * ({@code oj-api/CLAUDE.md} mục 4: <i>"không thêm chỗ thứ ba mà không hỏi"</i>).
 *
 * <h2>Sự kiện KHÔNG mang nội dung bảng, và đó là quyết định về bảo mật</h2>
 * {@link StandingsEventBus} chỉ báo "bảng của kỳ thi X vừa đổi". Mỗi kết nối tự đọc lại qua
 * {@link GetStandingsUseCase} — <b>và bộ lọc đóng băng chạy lại ở đó</b>.
 *
 * <p>Nếu sự kiện mang sẵn nội dung thì nội dung ấy là <i>một</i> bản, còn người xem thì có hai
 * loại: ADMIN thấy bảng đầy đủ, mọi người thấy bản chụp (FR-CON-05). Đẩy chung một bản nghĩa
 * là một trong hai nhóm nhận nhầm bản của nhóm kia — và nhóm nhận nhầm sẽ là nhóm thí sinh,
 * nhận bảng chưa công bố.
 *
 * <p>Cái giá: mỗi lần đổi là mỗi kết nối đọc lại một lượt. Với nhịp 2 giây và top 50 dòng thì
 * đó là cái giá rẻ, và nó mua lấy việc <b>không thể</b> lộ bảng chưa công bố.
 *
 * <h2>Fallback REST là bắt buộc</h2>
 * {@code GET /api/v1/contests/{id}/standings} tồn tại và trả cùng một thứ. Kết nối SSE
 * <i>sẽ</i> đứt — Cloudflare Tunnel giới hạn thời gian sống — và không có fallback thì tính
 * năng chưa xong.
 */
@RestController
@RequestMapping("/api/v1/contests")
public class StandingsSseController {

    private static final Logger log = LoggerFactory.getLogger(StandingsSseController.class);

    private final GetStandingsUseCase getStandings;
    private final StandingsEventBus bus;
    private final SseHeartbeat heartbeat;
    private final AppProperties properties;

    public StandingsSseController(GetStandingsUseCase getStandings, StandingsEventBus bus,
                                  SseHeartbeat heartbeat, AppProperties properties) {
        this.getStandings = getStandings;
        this.bus = bus;
        this.heartbeat = heartbeat;
        this.properties = properties;
    }

    @GetMapping(value = "/{contestId}/standings/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable long contestId) {
        SseEmitter emitter = new SseEmitter(properties.sse().timeout().toMillis());

        // Đọc một lần ngay TRƯỚC khi đăng ký nghe: nếu kỳ thi không tồn tại thì ném ở đây,
        // trước khi có bất kỳ kết nối nào được mở. Và nó cũng gửi trạng thái hiện tại, để
        // client không phải chờ thay đổi đầu tiên mới có gì hiển thị.
        SseEmitters.send(emitter, ContestResponses.BangXepHang.tu(getStandings.thucHien(contestId)));

        AutoCloseable dangKy = bus.subscribe(contestId, () -> {
            // Đọc lại qua use-case — bộ lọc đóng băng chạy lại cho ĐÚNG người này.
            SseEmitters.send(emitter,
                    ContestResponses.BangXepHang.tu(getStandings.thucHien(contestId)));
        });
        AutoCloseable ping = heartbeat.start(emitter);

        // Cả BA nhánh phải dọn: hết hạn, client đóng, và lỗi. Thiếu một nhánh là rò rỉ một
        // listener Redis cộng một tác vụ nhịp tim cho mỗi lần bấm F5 — và trang bảng xếp hạng
        // là trang người ta bấm F5 nhiều nhất trong cả kỳ thi.
        Runnable donDep = () -> {
            dongIm(ping);
            dongIm(dangKy);
        };
        emitter.onCompletion(donDep);
        emitter.onTimeout(() -> {
            donDep.run();
            emitter.complete();
        });
        emitter.onError(e -> donDep.run());
        return emitter;
    }

    private static void dongIm(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception e) {
            log.warn("Không dọn được tài nguyên của một luồng SSE bảng xếp hạng: {}", e.toString());
        }
    }
}
