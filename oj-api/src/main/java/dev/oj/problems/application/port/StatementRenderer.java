package dev.oj.problems.application.port;

public interface StatementRenderer {

    String render(String markdown);

    String version();
}
