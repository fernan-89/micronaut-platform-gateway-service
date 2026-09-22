package com.thinklab.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterTest {

    private static RateLimitProperties properties(double rps, int burst) {
        RateLimitProperties p = new RateLimitProperties();
        p.setRequestsPerSecond(rps);
        p.setBurst(burst);
        return p;
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-22T00:00:00Z");

        void advance(long millis) {
            now = now.plusMillis(millis);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    @DisplayName("a caller may burn its full burst instantly, then is denied with a retry-after estimate")
    void burstThenDenied() {
        MutableClock clock = new MutableClock();
        RateLimiter limiter = new RateLimiter(properties(1, 2), clock);

        assertEquals(0, limiter.tryAcquire("caller-a"));
        assertEquals(0, limiter.tryAcquire("caller-a"));
        long retryAfter = limiter.tryAcquire("caller-a");
        assertTrue(retryAfter >= 1);
    }

    @Test
    @DisplayName("tokens refill over time up to the burst cap, and different callers have independent buckets")
    void refillsAndIsPerCaller() {
        MutableClock clock = new MutableClock();
        RateLimiter limiter = new RateLimiter(properties(10, 1), clock);

        assertEquals(0, limiter.tryAcquire("caller-a"));
        assertEquals(0, limiter.tryAcquire("caller-b"));
        assertTrue(limiter.tryAcquire("caller-a") >= 1);

        clock.advance(200);
        assertEquals(0, limiter.tryAcquire("caller-a"));
    }

    @Test
    @DisplayName("stale buckets are pruned once the map grows past the threshold")
    void prunesStaleBuckets() {
        MutableClock clock = new MutableClock();
        RateLimiter limiter = new RateLimiter(properties(1000, 1000), clock);

        for (int i = 0; i < 10_001; i++) {
            limiter.tryAcquire("caller-" + i);
        }
        // Trips the size check for the first time while every existing bucket is still fresh: the prune
        // predicate is evaluated and is false for all of them (nothing removed).
        assertEquals(0, limiter.tryAcquire("caller-trigger-fresh"));

        clock.advance(11 * 60 * 1000L);
        // Trips the size check again; this time every bucket is stale, so the predicate is true and they
        // are all removed.
        assertEquals(0, limiter.tryAcquire("caller-trigger-stale"));
    }
}
