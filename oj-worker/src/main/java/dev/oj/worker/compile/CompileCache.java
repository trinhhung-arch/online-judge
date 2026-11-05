package dev.oj.worker.compile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Optional;

public final class CompileCache {

    private static final Logger log = LoggerFactory.getLogger(CompileCache.class);

    private final Path root;
    private final int maxEntries;

    public CompileCache(Path root, int maxEntries) {
        this.root = root;
        this.maxEntries = maxEntries;
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("Không tạo được thư mục cache biên dịch " + root, e);
        }
    }

    public Optional<byte[]> find(String key) {
        Path file = root.resolve(key);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.from(
                    java.time.Instant.now()));
            return Optional.of(Files.readAllBytes(file));
        } catch (IOException e) {
            log.warn("Không đọc được cache biên dịch {} — biên dịch lại: {}", key, e.toString());
            return Optional.empty();
        }
    }

    public void store(String key, byte[] binary) {
        try {
            Path temporary = Files.createTempFile(root, "bin-", ".part");
            Files.write(temporary, binary);
            Files.move(temporary, root.resolve(key), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            evict();
        } catch (IOException e) {
            log.warn("Không ghi được cache biên dịch {}: {}", key, e.toString());
        }
    }

    private void evict() throws IOException {
        try (var stream = Files.list(root)) {
            var files = stream.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(CompileCache::modifiedAt))
                    .toList();
            for (int i = 0; i < files.size() - maxEntries; i++) {
                Files.deleteIfExists(files.get(i));
            }
        }
    }

    private static long modifiedAt(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}
