package com.summercamp.project.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.summercamp.project.config.ResultPageProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ResultPageServiceTest {

    @Test
    void createsAnAccessibleUrlAndExpiresThePage() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-21T08:00:00Z"));
        ResultPageService service = new ResultPageService(
                new ResultPageProperties("http://192.168.1.20:8080/", 8080, Duration.ofMinutes(30)),
                clock,
                () -> "fixed-result-id");

        CalculationResultPage page = service.create("计算结果", "125 * 36", "4500");

        assertEquals("http://192.168.1.20:8080/results/fixed-result-id", service.publicUrl(page));
        assertEquals("4500", service.find(page.id()).orElseThrow().result());

        clock.advance(Duration.ofMinutes(31));
        assertTrue(service.find(page.id()).isEmpty());
    }

    @Test
    void controllerEscapesResultContentAndReturnsAnExpiryPage() {
        ResultPageService service = new ResultPageService(
                new ResultPageProperties("http://192.168.1.20:8080", 8080, Duration.ofMinutes(30)),
                Clock.fixed(Instant.parse("2026-08-21T08:00:00Z"), ZoneId.of("UTC")),
                () -> "safe-id");
        CalculationResultPage page = service.create(
                "<script>标题</script>", "1 < 2", "<img src=x onerror=alert(1)>");
        ResultPageController controller = new ResultPageController(service);

        var response = controller.show(page.id());
        var missing = controller.show("missing-id");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("&lt;script&gt;标题&lt;/script&gt;"));
        assertTrue(response.getBody().contains("1 &lt; 2"));
        assertFalse(response.getBody().contains("<img src=x"));
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
        assertTrue(missing.getBody().contains("链接不存在或已经过期"));
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
