package dev.oj.platform.settings.application;

import dev.oj.platform.audit.AuditLog;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.security.RequiresRole;
import dev.oj.platform.security.Role;
import dev.oj.platform.settings.SettingsException;
import dev.oj.platform.settings.SystemSettings;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ★ Bật/tắt công tắc lúc đang chạy — FR-ADM-06, và phanh tay của FR-ADM-01.
 *
 * <h2>Vì sao công tắc này cần một endpoint, chứ không phải một câu SQL</h2>
 * {@link SystemSettings} đã ghi lý do bảng {@code system_settings} tồn tại: ADMIN phải tắt
 * được nhận bài <i>lúc 2 giờ sáng giữa contest</i>. Cho tới trước lớp này, cách duy nhất là
 * mở {@code psql} và gõ một câu {@code UPDATE} — nghĩa là công tắc chỉ dùng được bởi người
 * có quyền vào database, từ một máy có sẵn client, trong lúc đang xử lý sự cố.
 *
 * <p>Và quan trọng hơn: <b>một câu {@code UPDATE} không để lại gì trong {@code audit_log}</b>.
 * Hệ thống này bán sự công bằng; "ai đã tắt nhận bài, lúc mấy giờ" là câu nó phải trả lời
 * được. Không trả lời được thì mọi khiếu nại về một bài nộp bị từ chối đều thành lời khai
 * của hai bên.
 *
 * <h2>Danh sách trắng, không phải khoá tự do</h2>
 * Nhận một {@code khoa} bất kỳ từ client là để ADMIN ghi một dòng rác vào bảng công tắc —
 * và những dòng ấy im lặng, vì {@link SystemSettings#bat} coi khoá lạ là "database không nói
 * gì" rồi dùng mặc định của người gọi. Nên chỉ hai khoá đi qua được.
 *
 * <p><b>{@code ai_review.enabled} cố ý KHÔNG có ở đây.</b> AI review là việc của tuần 14–15;
 * bày ra một công tắc bật được một tính năng chưa tồn tại là mời người ta bật nó. Khi tính
 * năng ấy có thật thì thêm khoá vào {@link #CHO_PHEP} — một dòng, và
 * {@code SystemSwitchAccessIT} sẽ nhắc nếu quên.
 *
 * <h2>Đổi công tắc KHÔNG đụng tới thứ đang chạy</h2>
 * Tắt nhận bài chỉ chặn <b>cửa vào</b>: {@code judge_queue} không bị đụng, nên mọi bài đã
 * commit vẫn đi hết đường của nó (R1). Đó là nửa sau của FR-ADM-06 và là nửa dễ quên — một
 * hiện thực "dọn hàng đợi cho sạch" sẽ làm mất bài giữa kỳ thi.
 */
@RequiresRole(Role.ADMIN)
@Service
public class ToggleSystemSwitchUseCase {

    /**
     * Khoá đổi được qua HTTP, và câu mô tả nghiệp vụ đi kèm mỗi khoá.
     *
     * <p>{@link SystemSettings#dat} cố ý không tự ghi {@code audit_log} vì mỗi công tắc cần
     * một câu khác nhau. Đây là chỗ giữ những câu ấy.
     */
    private static final Map<String, String> CHO_PHEP = Map.of(
            SystemSettings.NHAN_BAI_NOP,
            "Nhận bài nộp mới. Tắt = chế độ bảo trì; bài đang chấm vẫn chấm xong.",
            SystemSettings.REJUDGE,
            "Cho phép job chấm lại chạy. Tắt = phanh tay, job đã tạo cũng dừng lại.");

    /**
     * Giá trị khi khoá vắng mặt trong bảng — <b>phải khớp mặc định mà nơi TIÊU THỤ dùng</b>.
     *
     * <p>Cả bốn chỗ đọc hai khoá này ({@code SubmitSolutionUseCase}, {@code StatusController},
     * {@code StartRejudgeUseCase}, {@code RejudgeJobHandler}) đều truyền {@code true} — hỏng
     * theo hướng <i>vẫn phục vụ</i>, vì một bài đã vào {@code judge_queue} thì không mất được
     * nữa (R1). Nếu trang quản trị đọc cùng khoá ấy với mặc định {@code false} thì màn hình
     * nói "đang bảo trì" trong khi API vẫn nhận bài, và không ai biết bên nào đúng.
     *
     * <p>Thêm một khoá vào {@link #CHO_PHEP} có mặc định khác thì hằng này phải thành một
     * bảng tra — đừng để nó lặng lẽ sai cho khoá mới.
     */
    private static final boolean MAC_DINH = true;

    private final SystemSettings congTac;
    private final CurrentUserProvider currentUser;
    private final AuditLog auditLog;

    public ToggleSystemSwitchUseCase(SystemSettings congTac, CurrentUserProvider currentUser,
                                     AuditLog auditLog) {
        this.congTac = congTac;
        this.currentUser = currentUser;
        this.auditLog = auditLog;
    }

    /** Giá trị hiện tại của mọi công tắc đổi được. Thứ tự khai báo giữ nguyên cho ổn định. */
    public Map<String, CongTac> doc() {
        Map<String, CongTac> ra = new LinkedHashMap<>();
        for (String khoa : List.of(SystemSettings.NHAN_BAI_NOP, SystemSettings.REJUDGE)) {
            ra.put(khoa, new CongTac(khoa, congTac.bat(khoa, MAC_DINH),
                    CHO_PHEP.get(khoa)));
        }
        return ra;
    }

    /**
     * @param khoa phải nằm trong danh sách trắng
     * @return giá trị sau khi đổi, để chỗ gọi không phải đọc lại và không phải đoán
     */
    public CongTac dat(String khoa, boolean bat) {
        String moTa = CHO_PHEP.get(khoa);
        if (moTa == null) {
            throw SettingsException.khoaKhongDoiDuoc(khoa);
        }

        long nguoiDoi = currentUser.current().id();
        congTac.dat(khoa, bat, nguoiDoi);

        // Ghi SAU khi đổi: một dòng nhật ký cho một thay đổi không xảy ra còn tệ hơn không
        // có dòng nào, vì nó làm người điều tra tin vào một điều sai.
        auditLog.ghi("SYSTEM_SWITCH_SET", "system_setting", null,
                Map.of("khoa", khoa, "bat", bat));

        return new CongTac(khoa, bat, moTa);
    }

    /**
     * @param moTa câu giải thích hậu quả, gửi kèm để giao diện không phải tự viết lại — hai
     *             bản mô tả cho cùng một công tắc là hai bản sẽ lệch nhau
     */
    public record CongTac(String khoa, boolean bat, String moTa) {
    }
}
