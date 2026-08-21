package com.summercamp.project.result;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ResultPageController {

    private static final MediaType HTML_UTF8 = new MediaType(
            "text", "html", StandardCharsets.UTF_8);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));

    private final ResultPageService resultPageService;

    public ResultPageController(ResultPageService resultPageService) {
        this.resultPageService = resultPageService;
    }

    @GetMapping(value = "/results/{id}", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> show(@PathVariable String id) {
        return resultPageService.find(id)
                .map(page -> response(HttpStatus.OK, render(page)))
                .orElseGet(() -> response(HttpStatus.NOT_FOUND, renderMissing()));
    }

    private ResponseEntity<String> response(HttpStatus status, String body) {
        return ResponseEntity.status(status)
                .contentType(HTML_UTF8)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }

    private String render(CalculationResultPage page) {
        return pageTemplate(
                HtmlUtils.htmlEscape(page.title()),
                """
                <p class="label">计算表达式</p>
                <div class="expression">%s</div>
                <p class="label">计算结果</p>
                <div class="result">%s</div>
                <p class="time">生成时间：%s<br>失效时间：%s</p>
                """.formatted(
                        HtmlUtils.htmlEscape(page.expression()),
                        HtmlUtils.htmlEscape(page.result()),
                        TIME_FORMAT.format(page.createdAt()),
                        TIME_FORMAT.format(page.expiresAt())));
    }

    private String renderMissing() {
        return pageTemplate("结果页不可用", """
                <div class="result missing">链接不存在或已经过期</div>
                <p class="time">请回到微信重新生成计算结果二维码。</p>
                """);
    }

    private String pageTemplate(String title, String content) {
        return """
                <!doctype html>
                <html lang="zh-CN">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width,initial-scale=1">
                  <title>%s</title>
                  <style>
                    :root { color-scheme: light; font-family: system-ui, "Microsoft YaHei", sans-serif; }
                    * { box-sizing: border-box; }
                    body { margin: 0; min-height: 100vh; display: grid; place-items: center;
                           padding: 24px; color: #17352b;
                           background: linear-gradient(145deg, #e9fff6, #f7fbff); }
                    main { width: min(560px, 100%%); padding: 32px; border: 1px solid #cce8dc;
                           border-radius: 24px; background: rgba(255,255,255,.92);
                           box-shadow: 0 18px 50px rgba(24, 91, 68, .12); }
                    h1 { margin: 0 0 28px; font-size: clamp(24px, 6vw, 36px); }
                    .label { margin: 18px 0 8px; color: #547268; font-size: 14px; }
                    .expression { padding: 16px; border-radius: 14px; background: #f1f7f4;
                                  overflow-wrap: anywhere; font-family: ui-monospace, monospace; }
                    .result { color: #087f5b; font-size: clamp(38px, 13vw, 72px); font-weight: 750;
                              line-height: 1.15; overflow-wrap: anywhere; }
                    .missing { color: #a23c2a; font-size: 24px; }
                    .time { margin: 28px 0 0; color: #71847d; font-size: 13px; line-height: 1.7; }
                  </style>
                </head>
                <body><main><h1>%s</h1>%s</main></body>
                </html>
                """.formatted(title, title, content);
    }
}
