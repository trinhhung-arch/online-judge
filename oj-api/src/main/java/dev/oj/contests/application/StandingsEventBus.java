package dev.oj.contests.application;

/**
 * Báo và nghe thay đổi bảng xếp hạng của một kỳ thi — FR-CON-04, đường SSE thứ hai và cuối cùng
 * của hệ thống ({@code oj-api/CLAUDE.md} mục 4).
 *
 * <h2>Chỉ mang {@code contestId}, không mang nội dung bảng</h2>
 * Cùng quyết định với {@code SubmissionEvent} ở M3, và cùng lý do: <i>cách chắc chắn nhất để
 * không quên một bộ lọc là không có gì để lọc</i>.
 *
 * <p>Ở đây lý do còn nặng hơn. Bảng xếp hạng phải qua bộ lọc đóng băng (FR-CON-05): người
 * thường thấy bản chụp, ADMIN thấy bản đầy đủ. Nếu sự kiện mang sẵn nội dung thì nội dung ấy
 * là <b>một</b> bản, và một trong hai nhóm sẽ nhận nhầm bản của nhóm kia. Mang mỗi id thì mỗi
 * kết nối tự đọc lại bản đúng với vai trò của mình.
 *
 * <p>Đổi lại: mỗi lần đổi là mỗi kết nối đọc lại một lượt. Với nhịp 2 giây và top 50 dòng thì
 * đó là cái giá rẻ, và nó mua lấy việc không thể lộ bảng chưa công bố.
 */
public interface StandingsEventBus {

    /** Gọi <b>sau khi commit</b> — xem {@code StandingsUpdater.capNhat}. */
    void bangDaDoi(long contestId);

    /**
     * Nghe thay đổi của một kỳ thi. Trả về chính lệnh huỷ.
     *
     * <p>Không có nó thì mỗi lần người dùng bấm F5 để lại một listener sống mãi, và sau một
     * kỳ thi thì container giao mỗi thông điệp cho hàng nghìn kết nối đã chết từ lâu — cùng
     * cái bẫy đã gặp và đã ghi lại ở {@code RedisSubmissionEventBus} tại M3.
     */
    AutoCloseable subscribe(long contestId, Runnable khiDoi);
}
