package io.github.eolivelli.nb5visualizer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Renders the report data into the single-file HTML template. */
public final class HtmlReportGenerator {

    private static final String TEMPLATE_RESOURCE = "/report-template.html";

    public String generate(Map<String, Object> reportData) throws IOException {
        String template = loadTemplate();
        String title = String.valueOf(reportData.getOrDefault("title", "NoSQLBench run"));
        return template
                .replace("__TITLE__", escapeHtml(title))
                .replace("__DATA_JSON__", Json.write(reportData));
    }

    static String loadTemplate() throws IOException {
        try (InputStream in = HtmlReportGenerator.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
            if (in == null) {
                throw new IOException("Missing resource " + TEMPLATE_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
