package dev.oj.judging.application.port;

import java.util.Optional;

public interface LanguageRepository {

    Optional<Language> findEnabledByCode(String code);

    java.util.List<LanguageOption> listEnabled();

    record LanguageOption(short id, String code, String displayName, String versionLabel) {
    }

    record Language(int id, String code) {

        public Language {
            if (id <= 0) {
                throw new IllegalArgumentException("languages.id phải dương");
            }
        }
    }
}
