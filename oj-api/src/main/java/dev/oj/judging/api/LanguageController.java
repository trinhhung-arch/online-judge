package dev.oj.judging.api;

import dev.oj.judging.application.port.LanguageRepository;
import dev.oj.judging.application.usecase.ListLanguagesUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * {@code GET /api/v1/languages} — Bước 4.12.
 *
 * <p>Không phân trang, và đó là một ngoại lệ <b>hợp lệ</b> của bất biến #8: số dòng bị chặn
 * bởi bản chất của dữ liệu (một hệ thống có ba ngôn ngữ chấm, không phải ba nghìn), chứ không
 * bởi hy vọng. Xem javadoc của {@code LanguageRepository.listEnabled}.
 */
@RestController
@RequestMapping("/api/v1/languages")
public class LanguageController {

    private final ListLanguagesUseCase listLanguages;

    public LanguageController(ListLanguagesUseCase listLanguages) {
        this.listLanguages = listLanguages;
    }

    @GetMapping
    public List<LanguageRepository.LanguageOption> danhSach() {
        return listLanguages.thucHien();
    }
}
