package com.summercamp.project.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.summercamp.project.config.AgentOptimizationProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class CachingRagRetrieverTest {

    @Test
    void normalizesAndCachesEquivalentQueries() {
        AtomicInteger calls = new AtomicInteger();
        RagContext expected = new RagContext(List.of(), "context");
        RagRetriever delegate = query -> {
            calls.incrementAndGet();
            return expected;
        };
        AgentOptimizationProperties properties = new AgentOptimizationProperties(
                true, Duration.ofMinutes(10), Duration.ofMinutes(30), 20, 1200, 6, 1600);
        CachingRagRetriever retriever = new CachingRagRetriever(
                delegate, properties, Clock.fixed(Instant.parse("2026-08-27T00:00:00Z"), ZoneOffset.UTC));

        assertThat(retriever.retrieve("增肌 饮食")).isSameAs(expected);
        assertThat(retriever.retrieve("增肌，饮食")).isSameAs(expected);
        assertThat(calls).hasValue(1);
    }

    @Test
    void coalescesConcurrentEquivalentQueries() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        RagRetriever delegate = query -> {
            calls.incrementAndGet();
            try {
                Thread.sleep(80);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return RagContext.empty();
        };
        AgentOptimizationProperties properties = new AgentOptimizationProperties(
                true, Duration.ofMinutes(10), Duration.ofMinutes(30), 20, 1200, 6, 1600);
        CachingRagRetriever retriever = new CachingRagRetriever(
                delegate, properties, Clock.fixed(Instant.parse("2026-08-27T00:00:00Z"), ZoneOffset.UTC));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<RagContext>> futures = List.of(
                    executor.submit(() -> retriever.retrieve("增肌 饮食")),
                    executor.submit(() -> retriever.retrieve("增肌，饮食")),
                    executor.submit(() -> retriever.retrieve("增肌饮食")));
            for (var future : futures) {
                future.get();
            }
        }

        assertThat(calls).hasValue(1);
    }
}
