package dev.oj.judging.application.usecase;

import dev.oj.judging.application.port.LanguageRepository;
import dev.oj.platform.security.PublicAccess;
import org.springframework.stereotype.Service;

import java.util.List;

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
