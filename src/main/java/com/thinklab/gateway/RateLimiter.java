package com.thinklab.gateway;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token-bucket rate limiter keyed by caller. Each caller starts with a full bucket of {@code burst} tokens that
 * refills at {@code requestsPerSecond}; a request needs one token. Idle buckets are pruned so the map cannot grow
 * without bound.
 */
@Singleton
public class RateLimiter {

    private static final long PRUNE_AFTER_MILLIS = 10 * 60 * 1000L;
    private static final int PRUNE_THRESHOLD = 10_000;

    private final RateLimitProperties properties;
    private final Clock clock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Inject
    public RateLimiter(RateLimitProperties properties) {
        this(properties, Clock.systemUTC());
    }

    RateLimiter(RateLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** Consumes a token for the caller; returns 0 when allowed, or the seconds to wait (at least 1) when not. */
    public long tryAcquire(String caller) {
        long now = clock.millis();
        if (buckets.size() > PRUNE_THRESHOLD) {
            buckets.values().removeIf(bucket -> now - bucket.updatedAt > PRUNE_AFTER_MILLIS);
        }
        Bucket bucket = buckets.computeIfAbsent(caller, key -> new Bucket(properties.getBurst(), now));
        synchronized (bucket) {
            double elapsedSeconds = (now - bucket.updatedAt) / 1000.0;
            bucket.tokens = Math.min(properties.getBurst(), bucket.tokens + elapsedSeconds * properties.getRequestsPerSecond());
            bucket.updatedAt = now;
            if (bucket.tokens >= 1) {
                bucket.tokens -= 1;
                return 0;
            }
            return Math.max(1, (long) Math.ceil((1 - bucket.tokens) / properties.getRequestsPerSecond()));
        }
    }

    private static final class Bucket {
        private double tokens;
        private long updatedAt;

        private Bucket(double tokens, long updatedAt) {
            this.tokens = tokens;
            this.updatedAt = updatedAt;
        }
    }
}
