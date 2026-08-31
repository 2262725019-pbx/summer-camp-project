package com.summercamp.project.agent.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import com.summercamp.project.config.ResultPageProperties;
import com.summercamp.project.result.ResultPageService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class HealthPlanPageControllerTest {

    @Test
    void rendersAResponsiveEscapedHealthPlanPage() {
        ResultPageProperties properties = new ResultPageProperties(
                "http://192.168.1.8:8080", 8080, Duration.ofMinutes(30));
        ResultPageService resultPages = new ResultPageService(properties);
        HealthPlanPageService service = new HealthPlanPageService(
                properties,
                resultPages,
                Clock.fixed(Instant.parse("2026-08-25T00:00:00Z"), ZoneOffset.UTC),
                () -> "page-1");
        HealthPlanPage page = service.create(new HealthPlanArtifact(
                "七日计划 <script>", "第1天：训练 & 恢复", List.of(), List.of()));

        var response = new HealthPlanPageController(service).show(page.id());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("第1天：训练 &amp; 恢复", "七日计划 &lt;script&gt;");
        assertThat(response.getBody()).doesNotContain("<script>");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeaders().getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.getHeaders().getFirst("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(response.getHeaders().getFirst("Content-Security-Policy"))
                .contains("default-src 'none'", "frame-ancestors 'none'");
    }
}
