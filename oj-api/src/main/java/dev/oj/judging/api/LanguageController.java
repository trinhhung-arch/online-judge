package dev.oj.judging.api;

import dev.oj.judging.application.port.LanguageRepository;
import dev.oj.judging.application.usecase.ListLanguagesUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
