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
