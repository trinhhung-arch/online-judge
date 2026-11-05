package dev.oj.worker.testdata;

import dev.oj.worker.config.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class TestdataFetcher {

    private static final Logger log = LoggerFactory.getLogger(TestdataFetcher.class);

    private final ContentAddressedCache cache;
    private final TestdataSource source;
    private final int maxEntries;

    public TestdataFetcher(TestdataSource source, WorkerProperties properties) {
        this.source = source;
        this.cache = new ContentAddressedCache(
                properties.sandbox().cache().dir().resolve("testdata"));
        this.maxEntries = properties.sandbox().cache().maxEntries();
    }

    public Path fetch(String sha256) {
        return cache.find(sha256).orElseGet(() -> {
            log.debug("cache miss testdata {}", sha256);
            Path stored = cache.store(sha256, source.fetch(sha256));
            cache.evictDownTo(maxEntries);
            return stored;
        });
    }
}
