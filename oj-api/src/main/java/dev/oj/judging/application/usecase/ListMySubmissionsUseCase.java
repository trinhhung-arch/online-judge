package dev.oj.judging.application.usecase;

import dev.oj.judging.application.port.SubmissionRepository;
import dev.oj.judging.application.port.SubmissionRepository.SubmissionFilter;
import dev.oj.judging.application.port.SubmissionRepository.SubmissionListItem;
import dev.oj.judging.domain.JudgingException;
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.security.RequiresRole;
import dev.oj.platform.web.CursorPage;
import org.springframework.stereotype.Service;

/**
 * Lịch sử bài nộp của chính mình — FR-SUB-07. Cursor-based, mặc định 20, trần 50.
 *
 * <p>Ba điều bị cấm ở đây, cả ba đều là bất biến #8 nói bằng ba cách:
 * không {@code OFFSET} · không tổng số bản ghi · không danh sách nào thiếu {@code LIMIT}.
 * Bảng {@code submissions} sẽ có hàng triệu dòng, và một trang "xem tất cả" ra đời hôm nay
 * thì sống mãi mãi trong code ({@code frplan.md} Quy tắc 2).
 *
 * <p>Chỉ liệt kê bài của <b>chính người đang gọi</b> — id lấy từ {@code CurrentUserProvider},
 * không bao giờ từ tham số request. Nhận {@code userId} qua query param là tạo ra một endpoint
 * đọc trộm lịch sử của người khác, và nó sẽ được phát hiện bởi thí sinh trước khi bởi bạn.
 */
@RequiresRole  // sàn: phải đăng nhập. "của tôi" được ép bằng WHERE user_id = :userId
@Service
public class ListMySubmissionsUseCase {

    private final CurrentUserProvider currentUser;
    private final SubmissionRepository submissions;
    private final AppProperties.Page page;

    public ListMySubmissionsUseCase(CurrentUserProvider currentUser,
                                    SubmissionRepository submissions,
                                    AppProperties properties) {
        this.currentUser = currentUser;
        this.submissions = submissions;
        this.page = properties.page();
    }

    /**
     * @param cursor con trỏ của trang trước, {@code null} cho trang đầu
     * @param size   client xin bao nhiêu cũng được; xin 1000 thì nhận trần 50, <b>không nhận
     *               lỗi</b> ({@code oj-api/CLAUDE.md} mục 3)
     */
    public CursorPage<SubmissionListItem> list(String cursor, Integer size, SubmissionFilter filter) {
        long userId = currentUser.current().id();
        int pageSize = CursorPage.clampSize(size, page.defaultSize(), page.maxSize());
        return submissions.listForUser(
                userId,
                filter == null ? SubmissionFilter.none() : filter,
                parseCursor(cursor),
                pageSize);
    }

    /**
     * Con trỏ là {@code submissions.id} dạng chuỗi — {@code id} tăng đơn điệu nên
     * {@code ORDER BY id DESC} chính là thứ tự thời gian, và đó cũng là lý do bảng nóng không
     * cần index trên {@code created_at} ({@code postgres-design.md} mục 4).
     *
     * <p>Cursor hỏng thì từ chối, không im lặng quay về trang đầu: một client lặp vô hạn
     * trang đầu mà vẫn nhận 200 là loại lỗi không ai phát hiện ra trừ khi ngồi đếm.
     */
    private Long parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(cursor.trim());
        } catch (NumberFormatException e) {
            throw JudgingException.invalidCursor(cursor);
        }
    }
}
