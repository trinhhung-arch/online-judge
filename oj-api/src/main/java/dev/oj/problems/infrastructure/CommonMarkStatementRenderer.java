package dev.oj.problems.infrastructure;

import dev.oj.problems.application.port.StatementRenderer;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Render đề bài bằng CommonMark — FR-PROB-02, Bước 4.9.
 *
 * <h2>★ {@code escapeHtml(true)} là dòng quan trọng nhất file này</h2>
 * Đề bài do SETTER soạn và hiển thị cho <b>mọi thí sinh</b>. Nếu HTML thô đi qua được thì một
 * thẻ {@code <script>} trong đề là XSS trên trang mà cả kỳ thi đang mở — và nó đọc được access
 * token trong bộ nhớ trình duyệt của từng người.
 *
 * <p>Mối đe doạ không cần một SETTER cố ý phá: một tài khoản SETTER bị chiếm là đủ, và
 * {@code frplan.md} xếp SETTER là vai trò được tin cậy <i>một phần</i>, không phải tuyệt đối.
 *
 * <p>Chọn chặn tại nguồn thay vì lọc HTML sau khi render, vì bộ lọc HTML là loại mã mà lịch sử
 * ngành đã chứng minh không ai viết đúng — danh sách thẻ, danh sách thuộc tính, và một trăm
 * cách viết {@code javascript:} mà mỗi năm lại có thêm một cách mới. Không cho HTML vào thì
 * không có gì để lọc.
 *
 * <p>{@code sanitizeUrls(true)} lo nốt nửa còn lại: một liên kết Markdown hợp lệ
 * {@code [bấm](javascript:...)} không chứa HTML nào cả.
 *
 * <h2>LaTeX KHÔNG render ở đây — KaTeX vẽ ở trình duyệt</h2>
 * {@code build-order.md} Bước 4.9 viết "render Markdown+LaTeX server-side"; phần LaTeX được
 * quyết định làm ở client, và đây là chỗ ghi lại vì sao. Render LaTeX trong Java cần một
 * runtime JavaScript (GraalJS, ~50MB) chạy trong <b>cùng tiến trình đang giữ đường nộp bài</b>
 * — đổi một sự tiện lợi lấy một thành phần nặng trên đường nóng.
 *
 * <p>Phần đắt của việc render là phân tích Markdown, và <i>nó</i> vẫn được cache. Vẽ công thức
 * là việc rẻ, chạy một lần cho mỗi người xem, ở máy của họ. Codeforces và DMOJ đều làm vậy.
 *
 * <p>Hệ quả: các đoạn {@code $...$} đi qua nguyên vẹn dưới dạng văn bản đã escape, và trang đề
 * nạp KaTeX. <b>Đó là lý do {@link #version()} nhắc tên KaTeX</b> — đổi cách xử lý toán là
 * đổi kết quả người dùng thấy, nên cache phải hỏng theo.
 */
@Component
public class CommonMarkStatementRenderer implements StatementRenderer {

    /**
     * Vào khoá chính của {@code rendered_statements} cùng {@code statement_hash}.
     *
     * <p>Nâng phiên bản thư viện, đổi tuỳ chọn, hay đổi cách xử lý toán — đổi chuỗi này. Quên
     * đổi nghĩa là bản HTML cũ được phục vụ mãi mãi.
     */
    private static final String VERSION = "commonmark-0.24.0+katex-client-1";

    private final Parser parser = Parser.builder()
            .extensions(List.of(TablesExtension.create()))
            .build();

    private final HtmlRenderer renderer = HtmlRenderer.builder()
            .extensions(List.of(TablesExtension.create()))
            // ★ Hai dòng chống XSS. Xem javadoc của class trước khi đổi bất kỳ dòng nào.
            .escapeHtml(true)
            .sanitizeUrls(true)
            .build();

    @Override
    public String render(String markdown) {
        return markdown == null ? "" : renderer.render(parser.parse(markdown));
    }

    @Override
    public String version() {
        return VERSION;
    }
}
