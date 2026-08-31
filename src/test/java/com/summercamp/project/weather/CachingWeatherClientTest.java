package com.summercamp.project.weather;

import static org.assertj.core.api.Assertions.assertThat;

import com.summercamp.project.config.AgentOptimizationProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CachingWeatherClientTest {

    @Test
    void reusesTheSameSuccessfulWeatherQueryWithinTheTtl() {
        AtomicInteger calls = new AtomicInteger();
        WeatherClient delegate = (location, period) -> {
            calls.incrementAndGet();
            return new WeatherReport(location, "2026-08-27 08:00:00", period, null, List.of());
        };
        CachingWeatherClient client = new CachingWeatherClient(
                delegate, properties(), Clock.fixed(Instant.parse("2026-08-27T00:00:00Z"), ZoneOffset.UTC));

        WeatherReport first = client.query(" 上海 ", WeatherPeriod.THREE_DAYS);
        WeatherReport second = client.query("上海", WeatherPeriod.THREE_DAYS);

        assertThat(second).isSameAs(first);
        assertThat(calls).hasValue(1);
    }

    @Test
    void allowsDifferentLocationsToLoadConcurrently() throws Exception {
        CountDownLatch bothStarted = new CountDownLatch(2);
        AtomicBoolean overlapped = new AtomicBoolean(true);
        WeatherClient delegate = (location, period) -> {
            bothStarted.countDown();
            try {
                if (!bothStarted.await(1, TimeUnit.SECONDS)) {
                    overlapped.set(false);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return new WeatherReport(location, "2026-08-27 08:00:00", period, null, List.of());
        };
        CachingWeatherClient client = new CachingWeatherClient(
                delegate, properties(), Clock.fixed(Instant.parse("2026-08-27T00:00:00Z"), ZoneOffset.UTC));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var shanghai = executor.submit(() -> client.query("上海", WeatherPeriod.THREE_DAYS));
            var beijing = executor.submit(() -> client.query("北京", WeatherPeriod.THREE_DAYS));
            shanghai.get();
            beijing.get();
        }

        assertThat(overlapped).isTrue();
    }

    @Test
    void coalescesConcurrentLoadsForTheSameLocation() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        WeatherClient delegate = (location, period) -> {
            calls.incrementAndGet();
            try {
                Thread.sleep(80);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return new WeatherReport(location, "2026-08-27 08:00:00", period, null, List.of());
        };
        CachingWeatherClient client = new CachingWeatherClient(
                delegate, properties(), Clock.fixed(Instant.parse("2026-08-27T00:00:00Z"), ZoneOffset.UTC));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<WeatherReport>> futures = java.util.stream.IntStream.range(0, 6)
                    .mapToObj(index -> executor.submit(() -> client.query("上海", WeatherPeriod.THREE_DAYS)))
                    .toList();
            for (var future : futures) {
                future.get();
            }
        }

        assertThat(calls).hasValue(1);
    }

    private AgentOptimizationProperties properties() {
        return new AgentOptimizationProperties(
                true, Duration.ofMinutes(10), Duration.ofMinutes(30), 20, 1200, 6, 1600);
    }
}
