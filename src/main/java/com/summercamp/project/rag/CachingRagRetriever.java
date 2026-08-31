package com.summercamp.project.rag;

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
public class CachingRagRetriever implements RagRetriever {

    private static final Logger LOGGER = LoggerFactory.getLogger(CachingRagRetriever.class);

    private final RagRetriever delegate;
    private final AgentOptimizationProperties properties;
    private final Clock clock;
    private final Map<String, CacheEntry> cache = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<String, Object> loadLocks = new ConcurrentHashMap<>();

    @Autowired
    public CachingRagRetriever(KeywordRagRetriever delegate, AgentOptimizationProperties properties) {
        this(delegate, properties, Clock.systemUTC());
    }

    CachingRagRetriever(RagRetriever delegate, AgentOptimizationProperties properties, Clock clock) {
        this.delegate = delegate;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public RagContext retrieve(String query) {
        if (!properties.cacheEnabled()) {
            return delegate.retrieve(query);
        }
        properties.validate();
        String key = normalize(query);
        CacheEntry entry = findFresh(key, clock.instant());
        if (entry != null) {
            LOGGER.debug("RAG 缓存命中");
            return entry.context();
        }
        Object loadLock = loadLocks.computeIfAbsent(key, ignored -> new Object());
        try {
            synchronized (loadLock) {
                Instant now = clock.instant();
                entry = findFresh(key, now);
                if (entry != null) {
                    LOGGER.debug("RAG 缓存并发复用");
                    return entry.context();
                }
                LOGGER.debug("RAG 缓存未命中");
                RagContext context = delegate.retrieve(query);
                store(key, new CacheEntry(context, now.plus(properties.ragCacheTtl())));
                return context;
            }
        } finally {
            loadLocks.remove(key, loadLock);
        }
    }

    private CacheEntry findFresh(String key, Instant now) {
        synchronized (cache) {
            cache.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
            return cache.get(key);
        }
    }

    private void store(String key, CacheEntry entry) {
        synchronized (cache) {
            cache.put(key, entry);
            Iterator<String> iterator = cache.keySet().iterator();
            while (cache.size() > properties.cacheMaxEntries() && iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{S}\\s]+", "");
    }

    private record CacheEntry(RagContext context, Instant expiresAt) {
    }
}
