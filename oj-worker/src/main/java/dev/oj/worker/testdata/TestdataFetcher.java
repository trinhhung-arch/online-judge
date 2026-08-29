package dev.oj.worker.testdata;

import dev.oj.worker.config.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Lấy testdata: cache trước, nguồn xa sau. Bước 2.7 của {@code build-order.md}.
 *
 * <h2>Ba luật, và luật thứ ba là luật quan trọng nhất</h2>
 * <ol>
 *   <li>Có trong cache thì dùng luôn — đề trong contest được chấm hàng trăm lần.</li>
 *   <li>Không có thì tải, <b>băm lại</b>, rồi mới ghi cache. Nguồn xa không được tin.</li>
 *   <li><b>Trả về một {@link Path} trên host, không phải nội dung.</b> Bên gọi đưa đường dẫn
 *       này cho {@code ProcessBuilder.redirectInput} và nội dung đi vào chương trình qua một
 *       file descriptor đã mở sẵn — nên nó <b>không bao giờ nằm trong thư mục box</b>
 *       (bất biến #1). Nếu một ngày phương thức này đổi thành trả {@code byte[]} và bên gọi
 *       ghi nó vào box cho tiện, thì một chương trình bốn dòng liệt kê thư mục là lộ toàn bộ
 *       đáp án — test tấn công 10 tồn tại đúng để bắt ngày đó.</li>
 * </ol>
 */
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

    /** @return đường dẫn tới nội dung <b>trên host</b> — xem luật 3 trong javadoc của class */
    public Path fetch(String sha256) {
        return cache.find(sha256).orElseGet(() -> {
            // Chỉ log hash, không bao giờ log nội dung (bất biến #9, oj-worker/CLAUDE.md mục 7).
            log.debug("cache miss testdata {}", sha256);
            Path stored = cache.store(sha256, source.fetch(sha256));
            cache.evictDownTo(maxEntries);
            return stored;
        });
    }
}
