package dev.oj.worker.testdata;

import dev.oj.contract.Sha256;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * Cache đánh địa chỉ bằng nội dung. Bước 2.7 của {@code build-order.md}.
 *
 * <h2>Vì sao khoá là {@code sha256} của nội dung, không phải id của đề</h2>
 * Vì như thế thì <b>không cần cơ chế invalidate nào cả</b>. Tác giả sửa một testcase → hash
 * đổi → cache miss → tải bản mới. Không có thông báo nào phải gửi, không có TTL nào phải
 * chọn, và không có kịch bản "worker vẫn chấm bằng testdata cũ" — mà kịch bản đó là loại lỗi
 * tệ nhất của một OJ: hai thí sinh nộp cùng một bài, nhận hai verdict khác nhau, và không ai
 * chứng minh được chuyện gì đã xảy ra.
 *
 * <h2>Vì sao hash được kiểm lại mỗi lần ghi</h2>
 * Vì "content-addressed" chỉ có nghĩa khi nội dung <i>thật sự</i> khớp cái tên. Một byte hỏng
 * trên đường truyền mà lọt vào cache sẽ làm mọi bài nộp của đề đó sai <b>mãi mãi</b>, và
 * triệu chứng là "đề này ai nộp cũng WA" — người ta sẽ đi tìm lỗi trong đề, không ai nghĩ tới
 * cache trên máy chấm.
 *
 * <h2>Cache KHÔNG nằm trong box</h2>
 * Đây là bất biến #1. Thư mục này ở {@code oj.worker.sandbox.cache.dir}, ngoài
 * {@code box_root}, và {@code TestRunner} đưa nội dung vào chương trình qua stdin chứ không
 * copy file vào box.
 */
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

    /** Đường dẫn của nội dung nếu đã có. Không có thì {@code empty} — không tự đi tải. */
    public Optional<Path> find(String sha256) {
        Path file = pathOf(sha256);
        return Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
    }

    /**
     * Ghi nội dung vào cache và trả về đường dẫn.
     *
     * <p>Ghi ra file tạm rồi {@code ATOMIC_MOVE}: hai slot cùng tải một testcase là chuyện
     * thường (sáu slot chấm sáu bài của cùng một đề), và một lượt đọc thấy file <i>đang</i>
     * được ghi dở là một lượt chấm sai.
     */
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

    /**
     * Fanout hai ký tự đầu. Một thư mục phẳng chứa hàng chục nghìn file làm mọi lệnh
     * {@code ls} trên máy chấm treo vài giây, và đó luôn là lúc người ta đang gỡ lỗi.
     */
    private Path pathOf(String sha256) {
        if (!Sha256.isHex(sha256)) {
            throw new IllegalArgumentException("sha256 không hợp lệ: " + sha256);
        }
        String hex = sha256.toLowerCase(java.util.Locale.ROOT);
        return root.resolve(hex.substring(0, 2)).resolve(hex);
    }

    /** Dọn bớt khi cache quá {@code maxFiles}, bỏ file cũ nhất theo thời gian truy cập. */
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
            // Cache đầy chỉ làm chậm, không làm sai. Không có lý do gì để hỏng một lượt chấm.
            log.warn("Không dọn được cache {}: {}", root, e.toString());
        }
    }
}
