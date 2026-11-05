package dev.oj.worker.testdata;

import dev.oj.contract.Sha256;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

public final class ContentAddressedCache {

    private static final Logger log = LoggerFactory.getLogger(ContentAddressedCache.class);

    private final Path root;

    public ContentAddressedCache(Path root) {
        this.root = root;
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("Không tạo được thư mục cache " + root, e);
        }
    }

    public Optional<Path> find(String sha256) {
        Path file = pathOf(sha256);
        return Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
    }

    public Path store(String sha256, byte[] content) {
        String actual = Sha256.hexOf(content);
        if (!actual.equalsIgnoreCase(sha256)) {
            throw new TestdataUnavailableException(
                    "Nội dung không khớp hash: chờ " + sha256 + ", nhận " + actual
                            + ". Không ghi vào cache và không chấm bằng dữ liệu này");
        }
        Path file = pathOf(sha256);
        try {
            Files.createDirectories(file.getParent());
            Path temporary = Files.createTempFile(file.getParent(), "dl-", ".part");
            Files.write(temporary, content);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            return file;
        } catch (IOException e) {
            throw new TestdataUnavailableException("Không ghi được cache cho " + sha256, e);
        }
    }

    private Path pathOf(String sha256) {
        if (!Sha256.isHex(sha256)) {
            throw new IllegalArgumentException("sha256 không hợp lệ: " + sha256);
        }
        String hex = sha256.toLowerCase(java.util.Locale.ROOT);
        return root.resolve(hex.substring(0, 2)).resolve(hex);
    }

    public void evictDownTo(int maxFiles) {
        try (var stream = Files.walk(root)) {
            var files = stream.filter(Files::isRegularFile).sorted((a, b) -> {
                try {
                    return Files.getLastModifiedTime(a).compareTo(Files.getLastModifiedTime(b));
                } catch (IOException e) {
                    return 0;
                }
            }).toList();
            for (int i = 0; i < files.size() - maxFiles; i++) {
                Files.deleteIfExists(files.get(i));
            }
        } catch (IOException e) {
            log.warn("Không dọn được cache {}: {}", root, e.toString());
        }
    }
}
