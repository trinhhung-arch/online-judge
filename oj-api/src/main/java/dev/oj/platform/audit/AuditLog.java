package dev.oj.platform.audit;

import java.util.Map;

public interface AuditLog {

    void ghi(String hanhDong, String loaiThucThe, Long idThucThe, Map<String, Object> chiTiet);
}
