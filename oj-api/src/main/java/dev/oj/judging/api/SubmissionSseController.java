package dev.oj.judging.api;

import dev.oj.judging.application.usecase.WatchSubmissionUseCase;
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

        WatchSubmissionUseCase.Watch watch = watchSubmission.start(id, event -> {
            SseEmitters.send(emitter, event);
            if (event.isTerminal()) {
                emitter.complete();
            }
        });

        SseEmitters.send(emitter, watch.current());

        if (watch.alreadyFinished()) {
            emitter.complete();
            return emitter;
        }

        AutoCloseable ping = heartbeat.start(emitter);
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
