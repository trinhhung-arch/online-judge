package dev.oj.platform.settings;

import dev.oj.platform.error.DomainException;

public class SettingsException extends DomainException {

    private SettingsException(Kind kind, String code, String publicMessage, String logMessage) {
        super(kind, code, publicMessage, logMessage);
    }

    public static SettingsException khoaKhongDoiDuoc(String khoa) {
        return new SettingsException(Kind.INVALID, "settings.khoa_khong_doi_duoc",
                "Không đổi được công tắc này qua API.",
                "Khoá ngoài danh sách trắng: " + khoa);
    }
}
