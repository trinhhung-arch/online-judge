package dev.oj.platform.audit;

import java.util.Map;

/**
 * Ghi một sự kiện vào {@code audit_log} (V5) — append-only, phân mảnh theo tháng.
 *
 * <h2>Vì sao ở {@code platform} chứ không thuộc module nào</h2>
 * Vì bốn module sẽ cùng ghi vào đây: {@code identity} (ẩn danh hoá, vô hiệu hoá tài khoản),
 * {@code problems} (thay testdata — FR-PROB-10), {@code judging} (ẩn bài nộp — FR-SUB-09),
 * {@code contests} (đóng băng bảng xếp hạng). Cho một trong bốn sở hữu bảng này là ép ba
 * module còn lại phụ thuộc vào nó, và luật ArchUnit 3 cấm ba trong bốn chiều đó.
 *
 * <p>Interface này không biết nghiệp vụ nào cả — nó nhận chuỗi và số, đúng như luật 3b đòi ở
 * {@code platform}.
 *
 * <h2>Ghi audit KHÔNG được làm hỏng việc chính</h2>
 * Hiện thực nuốt mọi lỗi và chỉ ghi WARN. Cùng lập luận với {@code RedisSubmissionEventBus}:
 * hàm này được gọi <i>sau</i> khi việc thật đã commit, nên ném lỗi ở đây không cứu được gì mà
 * chỉ có thể phá. Mất một dòng audit là chuyện phải biết; làm hỏng một thao tác đã thành công
 * để giữ dòng audit đó thì tệ hơn.
 *
 * <p><b>Không ghi nội dung nhạy cảm vào {@code chiTiet}.</b> Bảng này ADMIN đọc được
 * (FR-ADM-02), nên nó chịu đúng bất biến #9 như log: không mật khẩu, không token, không
 * source người dùng, không nội dung testcase.
 */
public interface AuditLog {

    /**
     * @param hanhDong   động từ ở dạng hằng, chữ hoa: {@code USER_ANONYMIZED},
     *                   {@code PASSWORD_CHANGED}, {@code PROBLEM_TESTDATA_REPLACED}
     * @param loaiThucThe tên bảng hoặc khái niệm: {@code user}, {@code problem}, {@code submission}
     * @param idThucThe  khoá chính của thực thể bị tác động, có thể {@code null}
     * @param chiTiet    dữ liệu phụ, sẽ thành JSONB. Rỗng thì truyền {@link Map#of()}
     */
    void ghi(String hanhDong, String loaiThucThe, Long idThucThe, Map<String, Object> chiTiet);
}
