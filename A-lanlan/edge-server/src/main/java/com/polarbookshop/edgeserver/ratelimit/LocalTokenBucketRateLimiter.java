package com.polarbookshop.edgeserver.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;

@Deprecated
public final class LocalTokenBucketRateLimiter implements ResilientRedisRateLimiter.LocalRateLimitRunner {

    private static final String REMAINING_HEADER = "X-RateLimit-Remaining";

    private final Clock clock;
    private final Duration entryTtl;
    private final Duration cleanupInterval;
    private final ConcurrentHashMap<String, BucketState> buckets = new ConcurrentHashMap<>();

    private volatile Instant nextCleanupAt;

    public LocalTokenBucketRateLimiter(Clock clock, Duration entryTtl, Duration cleanupInterval) {
        this.clock = clock;
        this.entryTtl = entryTtl;
        this.cleanupInterval = cleanupInterval;
        this.nextCleanupAt = clock.instant().plus(cleanupInterval);
    }

    public RateLimiter.Response isAllowed(
            String routeId,
            String key,
            int replenishRate,
            long burstCapacity,
            int requestedTokens
    ) {
        Instant now = clock.instant();
        cleanupIfNeeded(now);

        BucketState state = buckets.computeIfAbsent(routeId + ":" + key, ignored -> new BucketState(now, burstCapacity));
        synchronized (state) {
            refill(state, now, replenishRate, burstCapacity);
            state.lastSeen = now;
            boolean allowed = state.tokens >= requestedTokens;
            if (allowed) {
                state.tokens -= requestedTokens;
            }
            return new RateLimiter.Response(allowed, Map.of(REMAINING_HEADER, Long.toString(state.tokens)));
        }
    }

    @Override
    public RateLimiter.Response run(String routeId, String key, ResilientRedisRateLimiter.Config config) {
        return isAllowed(routeId, key, config.getReplenishRate(), config.getBurstCapacity(), config.getRequestedTokens());
    }

    int bucketCount() {
        return buckets.size();
    }

    private void cleanupIfNeeded(Instant now) {
        if (now.isBefore(nextCleanupAt)) {
            return;
        }
        buckets.entrySet().removeIf(entry -> Duration.between(entry.getValue().lastSeen, now).compareTo(entryTtl) > 0);
        nextCleanupAt = now.plus(cleanupInterval);
    }

    private void refill(BucketState state, Instant now, int replenishRate, long burstCapacity) {
        long elapsedSeconds = Duration.between(state.lastRefillAt, now).getSeconds();
        if (elapsedSeconds <= 0) {
            return;
        }
        long replenishedTokens = elapsedSeconds * replenishRate;
        state.tokens = Math.min(burstCapacity, state.tokens + replenishedTokens);
        state.lastRefillAt = state.lastRefillAt.plusSeconds(elapsedSeconds);
    }

    private static final class BucketState {

        private long tokens;
        private Instant lastRefillAt;
        private Instant lastSeen;

        private BucketState(Instant now, long burstCapacity) {
            this.tokens = burstCapacity;
            this.lastRefillAt = now;
            this.lastSeen = now;
        }

    }
}
