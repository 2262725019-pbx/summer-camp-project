package com.summercamp.project.agent.artifact;

import com.summercamp.project.config.ResultPageProperties;
import com.summercamp.project.result.ResultPageService;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class HealthPlanPageService {

    private static final int MAX_CONTENT_LENGTH = 60_000;
    private final ConcurrentHashMap<String, HealthPlanPage> pages = new ConcurrentHashMap<>();
    private final ResultPageProperties properties;
    private final ResultPageService resultPageService;
    private final Clock clock;
    private final Supplier<String> idSupplier;

    @Autowired
    public HealthPlanPageService(ResultPageProperties properties, ResultPageService resultPageService) {
        this(properties, resultPageService, Clock.systemUTC(), () -> UUID.randomUUID().toString());
    }

    HealthPlanPageService(
            ResultPageProperties properties,
            ResultPageService resultPageService,
            Clock clock,
            Supplier<String> idSupplier) {
        this.properties = properties;
        this.resultPageService = resultPageService;
        this.clock = clock;
        this.idSupplier = idSupplier;
    }

    public HealthPlanPage create(HealthPlanArtifact artifact) {
        if (artifact.content().isBlank() || artifact.content().length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("健康计划内容必须为 1～60000 个字符");
        }
        cleanupExpired();
        Instant now = clock.instant();
        HealthPlanPage page = new HealthPlanPage(
                idSupplier.get(), artifact.title(), artifact.content(), now, now.plus(properties.ttl()));
        pages.put(page.id(), page);
        return page;
    }

    public Optional<HealthPlanPage> find(String id) {
        HealthPlanPage page = pages.get(id);
        if (page == null) {
            return Optional.empty();
        }
        if (!page.expiresAt().isAfter(clock.instant())) {
            pages.remove(id, page);
            return Optional.empty();
        }
        return Optional.of(page);
    }

    public String publicUrl(HealthPlanPage page) {
        return resultPageService.publicBaseUrl() + "/health-plans/" + page.id();
    }

    private void cleanupExpired() {
        Instant now = clock.instant();
        pages.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }
}
