package dev.oj.contests.api;

import dev.oj.contests.application.StandingsEventBus;
import dev.oj.contests.application.usecase.GetStandingsUseCase;
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.security.CurrentUserProvider.CurrentUser;
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

        CurrentUser nguoiGoi = getStandings.nguoiGoiHienTai();

        SseEmitters.send(emitter,
                ContestResponses.BangXepHang.tu(getStandings.thucHien(contestId, nguoiGoi)));

        AutoCloseable dangKy = bus.subscribe(contestId, () -> {
            SseEmitters.send(emitter,
                    ContestResponses.BangXepHang.tu(getStandings.thucHien(contestId, nguoiGoi)));
        });
        AutoCloseable ping = heartbeat.start(emitter);

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
