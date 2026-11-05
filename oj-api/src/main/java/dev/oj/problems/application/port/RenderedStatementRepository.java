package dev.oj.problems.application.port;

import java.util.Optional;

public interface RenderedStatementRepository {

    Optional<String> tim(String statementHash, String rendererVersion);

    void luu(String statementHash, String rendererVersion, String html);
}
