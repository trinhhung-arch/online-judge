package dev.oj.problems.infrastructure;

import dev.oj.problems.application.port.StatementRenderer;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CommonMarkStatementRenderer implements StatementRenderer {

    private static final String VERSION = "commonmark-0.24.0+katex-client-1";

    private final Parser parser = Parser.builder()
            .extensions(List.of(TablesExtension.create()))
            .build();

    private final HtmlRenderer renderer = HtmlRenderer.builder()
            .extensions(List.of(TablesExtension.create()))
            .escapeHtml(true)
            .sanitizeUrls(true)
            .build();

    @Override
    public String render(String markdown) {
        return markdown == null ? "" : renderer.render(parser.parse(markdown));
    }

    @Override
    public String version() {
        return VERSION;
    }
}
