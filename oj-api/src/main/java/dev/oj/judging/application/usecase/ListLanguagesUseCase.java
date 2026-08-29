package dev.oj.judging.application.usecase;

import dev.oj.judging.application.port.LanguageRepository;
import dev.oj.platform.security.PublicAccess;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Ngôn ngữ đang bật — Bước 4.12, phục vụ ô chọn trên trang nộp bài.
 *
 * <p>Giữ đúng NFR M4: <i>"thêm 1 ngôn ngữ chấm = 1 dòng config, 0 dòng code"</i>. Frontend
 * đọc từ đây thay vì gán cứng ba mã, nên bật một ngôn ngữ trong bảng {@code languages} là
 * nó xuất hiện ngay, không cần deploy lại gì cả.
 */
@PublicAccess("Danh sách ngôn ngữ được hỗ trợ là thông tin công khai — nó nằm trên trang đề "
        + "để người ta biết nộp được bằng gì trước khi đăng ký tài khoản.")
@Service
public class ListLanguagesUseCase {

    private final LanguageRepository languages;

    public ListLanguagesUseCase(LanguageRepository languages) {
        this.languages = languages;
    }

    public List<LanguageRepository.LanguageOption> thucHien() {
        return languages.listEnabled();
    }
}
