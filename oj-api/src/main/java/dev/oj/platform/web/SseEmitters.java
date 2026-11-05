package dev.oj.platform.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public final class SseEmitters {

    private static final Logger log = LoggerFactory.getLogger(SseEmitters.class);

    private SseEmitters() {
    }

    public static boolean send(SseEmitter emitter, Object payload) {
        return attempt(emitter, SseEmitter.event().name("submission").data(payload));
    }

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

    private static void closeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
        }
    }
}
