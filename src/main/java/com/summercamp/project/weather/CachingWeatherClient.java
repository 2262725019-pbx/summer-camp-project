package com.summercamp.project.weather;

import com.summercamp.project.config.AgentOptimizationProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class CachingWeatherClient implements WeatherClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(CachingWeatherClient.class);

    private final WeatherClient delegate;
    private final AgentOptimizationProperties properties;
    private final Clock clock;
    private final Map<CacheKey, CacheEntry> cache = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<CacheKey, Object> loadLocks = new ConcurrentHashMap<>();

    @Autowired
    public CachingWeatherClient(AmapWeatherClient delegate, AgentOptimizationProperties properties) {
        this(delegate, properties, Clock.systemUTC());
    }

    CachingWeatherClient(WeatherClient delegate, AgentOptimizationProperties properties, Clock clock) {
        this.delegate = delegate;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public WeatherReport query(String location, WeatherPeriod period) {
        if (!properties.cacheEnabled()) {
            return delegate.query(location, period);
        }
        properties.validate();
        CacheKey key = new CacheKey(normalize(location), period);
        CacheEntry entry = findFresh(key, clock.instant());
        if (entry != null) {
            LOGGER.debug("天气缓存命中：period={}", period);
            return entry.report();
        }
        Object loadLock = loadLocks.computeIfAbsent(key, ignored -> new Object());
        try {
            synchronized (loadLock) {
                Instant now = clock.instant();
                entry = findFresh(key, now);
                if (entry != null) {
                    LOGGER.debug("天气缓存并发复用：period={}", period);
                    return entry.report();
                }
                LOGGER.debug("天气缓存未命中：period={}", period);
                WeatherReport report = delegate.query(location, period);
                store(key, new CacheEntry(report, now.plus(properties.weatherCacheTtl())));
                return report;
            }
        } finally {
            loadLocks.remove(key, loadLock);
        }
    }

    private CacheEntry findFresh(CacheKey key, Instant now) {
        synchronized (cache) {
            cache.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
            return cache.get(key);
        }
    }

    private void store(CacheKey key, CacheEntry entry) {
        synchronized (cache) {
            cache.put(key, entry);
            Iterator<CacheKey> iterator = cache.keySet().iterator();
            while (cache.size() > properties.cacheMaxEntries() && iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private record CacheKey(String location, WeatherPeriod period) {
    }

    private record CacheEntry(WeatherReport report, Instant expiresAt) {
    }
}
