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

@RequiresRole(Role.ADMIN)
@Service
public class ToggleSystemSwitchUseCase {

    private static final Map<String, String> CHO_PHEP = Map.of(
            SystemSettings.NHAN_BAI_NOP,
            "Nhận bài nộp mới. Tắt = chế độ bảo trì; bài đang chấm vẫn chấm xong.",
            SystemSettings.REJUDGE,
            "Cho phép job chấm lại chạy. Tắt = phanh tay, job đã tạo cũng dừng lại.");

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

    public Map<String, CongTac> doc() {
        Map<String, CongTac> ra = new LinkedHashMap<>();
        for (String khoa : List.of(SystemSettings.NHAN_BAI_NOP, SystemSettings.REJUDGE)) {
            ra.put(khoa, new CongTac(khoa, congTac.bat(khoa, MAC_DINH),
                    CHO_PHEP.get(khoa)));
        }
        return ra;
    }

    public CongTac dat(String khoa, boolean bat) {
        String moTa = CHO_PHEP.get(khoa);
        if (moTa == null) {
            throw SettingsException.khoaKhongDoiDuoc(khoa);
        }

        long nguoiDoi = currentUser.current().id();
        congTac.dat(khoa, bat, nguoiDoi);

        auditLog.ghi("SYSTEM_SWITCH_SET", "system_setting", null,
                Map.of("khoa", khoa, "bat", bat));

        return new CongTac(khoa, bat, moTa);
    }

    public record CongTac(String khoa, boolean bat, String moTa) {
    }
}
