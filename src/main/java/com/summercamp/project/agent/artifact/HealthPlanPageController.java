package com.summercamp.project.agent.artifact;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

@RestController
public class HealthPlanPageController {

    private static final MediaType HTML_UTF8 = new MediaType("text", "html", StandardCharsets.UTF_8);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));
    private final HealthPlanPageService service;

    public HealthPlanPageController(HealthPlanPageService service) {
        this.service = service;
    }

    @GetMapping(value = "/health-plans/{id}", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> show(@PathVariable String id) {
        return service.find(id)
                .map(page -> response(HttpStatus.OK, render(page)))
                .orElseGet(() -> response(HttpStatus.NOT_FOUND, renderMissing()));
    }

    private ResponseEntity<String> response(HttpStatus status, String body) {
        return ResponseEntity.status(status)
                .contentType(HTML_UTF8)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header("X-Frame-Options", "DENY")
                .header("Referrer-Policy", "no-referrer")
                .header("Content-Security-Policy",
                        "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'")
                .body(body);
    }

    private String render(HealthPlanPage page) {
        return template(
                HtmlUtils.htmlEscape(page.title()),
                "<pre>" + HtmlUtils.htmlEscape(page.content()) + "</pre>"
                        + "<p class=\"time\">生成时间：" + TIME_FORMAT.format(page.createdAt())
                        + "<br>失效时间：" + TIME_FORMAT.format(page.expiresAt()) + "</p>");
    }

    private String renderMissing() {
        return template("健康计划不可用", "<p>链接不存在或已经过期，请回到微信重新生成。</p>");
    }

    private String template(String title, String content) {
        return """
                <!doctype html>
                <html lang="zh-CN"><head><meta charset="UTF-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>%s</title><style>
                :root{font-family:system-ui,"Microsoft YaHei",sans-serif;color:#17352b;background:#edf8f2}
                body{margin:0;padding:20px}main{max-width:820px;margin:auto;background:white;border-radius:22px;
                padding:clamp(20px,5vw,44px);box-shadow:0 18px 55px rgba(24,91,68,.12)}
                h1{color:#087f5b;margin-top:0}pre{font:inherit;white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.8}
                .time{color:#71847d;font-size:13px;border-top:1px solid #dbece4;padding-top:18px}
                </style></head><body><main><h1>%s</h1>%s</main></body></html>
                """.formatted(title, title, content);
    }
}
