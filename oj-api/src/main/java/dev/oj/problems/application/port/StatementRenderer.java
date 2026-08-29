package dev.oj.problems.application.port;

/**
 * Markdown → HTML cho đề bài. FR-PROB-02, Bước 4.9.
 *
 * <p>Là một port vì {@link #version()} phải vào khoá cache, và một cache có khoá phiên bản chỉ
 * có nghĩa nếu bộ render <b>thay được</b> — nếu không thì trường ấy là trang trí.
 */
public interface StatementRenderer {

    /**
     * @param markdown nội dung do SETTER soạn. <b>Không tin</b> — xem hiện thực
     * @return HTML an toàn để nhúng thẳng vào trang
     */
    String render(String markdown);

    /**
     * Định danh phiên bản bộ render, vào khoá chính của {@code rendered_statements}.
     *
     * <p><b>Đổi bộ render là phải đổi chuỗi này.</b> Không đổi thì bản HTML cũ vẫn được phục
     * vụ mãi mãi, và một bản vá bảo mật cho bộ render sẽ không có tác dụng với bất kỳ đề nào
     * đã từng được xem — tức là với mọi đề đang dùng.
     */
    String version();
}
