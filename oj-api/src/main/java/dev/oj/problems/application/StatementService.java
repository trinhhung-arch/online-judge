package dev.oj.problems.application;

import dev.oj.problems.application.port.RenderedStatementRepository;
import dev.oj.problems.application.port.StatementRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * HTML của một đề, có cache — FR-PROB-02, P1.
 *
 * <h2>Cache-aside, và cache hỏng KHÔNG được làm hỏng trang đề</h2>
 * Đọc cache → trượt thì render → ghi cache. Nếu bước ghi hỏng (database đầy, quyền sai), trang
 * vẫn trả HTML đúng và chỉ tốn một lần render nữa ở lần sau. Cùng lập luận với
 * {@code RedisSubmissionEventBus}: một cache là thứ tăng tốc, không phải thứ mà sự đúng đắn
 * phụ thuộc vào.
 *
 * <h2>Vì sao đây là {@code Service} chứ không phải {@code UseCase}</h2>
 * Nó không phải một hành động người dùng yêu cầu — không ai bấm "render đề". Nó là một bước
 * bên trong {@code GetProblemUseCase}, và nếu mang tên {@code UseCase} thì LUẬT 8 sẽ bắt nó
 * tuyên bố lập trường phân quyền, mà câu trả lời đúng lại là <i>"tuỳ người gọi"</i>.
 */
@Service
public class StatementService {

    private static final Logger log = LoggerFactory.getLogger(StatementService.class);

    private final StatementRenderer renderer;
    private final RenderedStatementRepository cache;

    public StatementService(StatementRenderer renderer, RenderedStatementRepository cache) {
        this.renderer = renderer;
        this.cache = cache;
    }

    public String html(String statementHash, String statementMd) {
        try {
            var daCo = cache.tim(statementHash, renderer.version());
            if (daCo.isPresent()) {
                return daCo.get();
            }
        } catch (RuntimeException e) {
            log.warn("Đọc cache bản render hỏng: {}. Render lại.", e.toString());
        }

        String html = renderer.render(statementMd);
        try {
            cache.luu(statementHash, renderer.version(), html);
        } catch (RuntimeException e) {
            log.warn("Ghi cache bản render hỏng: {}. Trang đề vẫn đúng, chỉ chậm hơn.",
                    e.toString());
        }
        return html;
    }

    /**
     * {@code sha256(statement_md)} — khớp cột {@code problems.statement_hash} (V2).
     *
     * <p>Hàm này là <b>chỗ duy nhất</b> tính giá trị ấy. Một chỗ thứ hai tính khác đi một
     * khoảng trắng là một cache không bao giờ trúng, và triệu chứng duy nhất là trang đề chậm
     * — thứ không ai nối được với nguyên nhân.
     */
    public static String bam(String statementMd) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((statementMd == null ? "" : statementMd)
                            .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM không có SHA-256", e);
        }
    }
}
