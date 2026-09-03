package dev.oj.platform.settings;

/**
 * Công tắc bật/tắt lúc đang chạy — bảng {@code system_settings} (V1).
 *
 * <h2>Ranh giới với {@code application.yml}, và vì sao nó quan trọng</h2>
 * {@code CLAUDE.md} mục 7 bắt mọi ngưỡng phải là thuộc tính có tên trong {@code application.yml}.
 * Bảng này <b>không</b> mâu thuẫn với điều đó — nó chứa loại giá trị khác:
 *
 * <ul>
 *   <li><b>{@code application.yml}</b> — thứ đổi thì phải deploy lại, và deploy lại là chuyện
 *       bình thường: lease 120s, rate limit 10s, kích thước trang.</li>
 *   <li><b>Bảng này</b> — thứ ADMIN phải bật/tắt được <b>lúc 2 giờ sáng giữa contest</b>. Nếu
 *       tắt nhận bài đòi hỏi một lần deploy thì trong sự cố nó sẽ không được dùng, và cái
 *       công tắc đó chỉ tồn tại trên giấy.</li>
 * </ul>
 *
 * <h2>Ở {@code platform} vì ba module cùng cần</h2>
 * {@code judging} đọc {@code submissions.accepting} (FR-ADM-06), {@code ai} đọc
 * {@code ai_review.enabled} (FR-AI-09), và trang trạng thái công khai đọc cả hai. Cho
 * {@code judging} sở hữu bảng này là ép {@code ai} phụ thuộc {@code judging} — chiều mà luật
 * ArchUnit 3 không có.
 */
public interface SystemSettings {

    /** FR-ADM-06 — {@code false} nghĩa là đang bảo trì, API từ chối bài nộp mới. */
    String NHAN_BAI_NOP = "submissions.accepting";

    /** FR-AI-09 — kill switch của AI review (tuần 14–15). */
    String AI_REVIEW = "ai_review.enabled";

    /** Chặn mọi job rejudge, kể cả job đã tạo — phanh tay cho FR-ADM-01. */
    String REJUDGE = "rejudge.enabled";

    /**
     * @param macDinh giá trị khi khoá không tồn tại <b>hoặc khi không đọc được database</b>.
     *        Hiện thực không ném: một công tắc không đọc được không được phép làm sập đường
     *        nộp bài, và {@code true} (nhận bài) là hướng hỏng an toàn — bài vào
     *        {@code judge_queue} thì không mất được nữa (R1)
     */
    boolean bat(String khoa, boolean macDinh);

    /**
     * Đổi một công tắc. Chỉ ADMIN gọi tới đây, và người gọi <b>phải tự ghi {@code audit_log}</b>
     * — hiện thực cố ý không ghi hộ, vì mỗi công tắc cần một câu mô tả nghiệp vụ khác nhau.
     */
    void dat(String khoa, boolean giaTri, Long nguoiDoi);
}
