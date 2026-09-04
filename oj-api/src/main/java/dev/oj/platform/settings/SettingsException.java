package dev.oj.platform.settings;

import dev.oj.platform.error.DomainException;

/**
 * Lỗi của vùng công tắc lúc đang chạy — {@code CLAUDE.md} mục 7: mỗi module một exception
 * riêng, không ném {@code RuntimeException} trần.
 */
public class SettingsException extends DomainException {

    private SettingsException(Kind kind, String code, String publicMessage, String logMessage) {
        super(kind, code, publicMessage, logMessage);
    }

    /**
     * Khoá không nằm trong danh sách trắng của {@code ToggleSystemSwitchUseCase}.
     *
     * <p>Câu công khai <b>không nhắc lại khoá client gửi</b>: một endpoint lặp lại đầu vào là
     * một endpoint dùng để dò xem khoá nào có thật. Khoá đi vào log, nơi nó có ích cho người
     * vận hành và vô dụng cho người dò.
     */
    public static SettingsException khoaKhongDoiDuoc(String khoa) {
        return new SettingsException(Kind.INVALID, "settings.khoa_khong_doi_duoc",
                "Không đổi được công tắc này qua API.",
                "Khoá ngoài danh sách trắng: " + khoa);
    }
}
