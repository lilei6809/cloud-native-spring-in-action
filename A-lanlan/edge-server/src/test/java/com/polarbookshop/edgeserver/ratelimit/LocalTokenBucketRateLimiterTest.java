package com.polarbookshop.edgeserver.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;

import static org.assertj.core.api.Assertions.assertThat;

class LocalTokenBucketRateLimiterTest {

    @Test
    void allowsRequestsWhileTokensRemain() {
        MutableClock clock = new MutableClock(Instant.parse("2026-03-10T00:00:00Z"));
        LocalTokenBucketRateLimiter rateLimiter = new LocalTokenBucketRateLimiter(
                clock, Duration.ofMinutes(5), Duration.ofSeconds(30));

        RateLimiter.Response first = rateLimiter.isAllowed("orders", "alice", 2, 3, 1);
        RateLimiter.Response second = rateLimiter.isAllowed("orders", "alice", 2, 3, 1);
        RateLimiter.Response third = rateLimiter.isAllowed("orders", "alice", 2, 3, 1);

        assertThat(first.isAllowed()).isTrue();
        assertThat(second.isAllowed()).isTrue();
        assertThat(third.isAllowed()).isTrue();
    }

    @Test
    void deniesWhenBurstCapacityIsExhausted() {
        MutableClock clock = new MutableClock(Instant.parse("2026-03-10T00:00:00Z"));
        LocalTokenBucketRateLimiter rateLimiter = new LocalTokenBucketRateLimiter(
                clock, Duration.ofMinutes(5), Duration.ofSeconds(30));

        rateLimiter.isAllowed("orders", "alice", 1, 2, 1);
        rateLimiter.isAllowed("orders", "alice", 1, 2, 1);
        RateLimiter.Response denied = rateLimiter.isAllowed("orders", "alice", 1, 2, 1);

        assertThat(denied.isAllowed()).isFalse();
    }

    @Test
    void replenishesTokensAfterTimeAdvances() {
        MutableClock clock = new MutableClock(Instant.parse("2026-03-10T00:00:00Z"));
        LocalTokenBucketRateLimiter rateLimiter = new LocalTokenBucketRateLimiter(
                clock, Duration.ofMinutes(5), Duration.ofSeconds(30));

        rateLimiter.isAllowed("orders", "alice", 1, 1, 1);
        RateLimiter.Response denied = rateLimiter.isAllowed("orders", "alice", 1, 1, 1);
        clock.advance(Duration.ofSeconds(1));
        RateLimiter.Response allowedAgain = rateLimiter.isAllowed("orders", "alice", 1, 1, 1);

        assertThat(denied.isAllowed()).isFalse();
        assertThat(allowedAgain.isAllowed()).isTrue();
    }

    @Test
    void isolatesBucketsByRouteAndKey() {
        MutableClock clock = new MutableClock(Instant.parse("2026-03-10T00:00:00Z"));
        LocalTokenBucketRateLimiter rateLimiter = new LocalTokenBucketRateLimiter(
                clock, Duration.ofMinutes(5), Duration.ofSeconds(30));

        rateLimiter.isAllowed("orders", "alice", 1, 1, 1);
        RateLimiter.Response differentRoute = rateLimiter.isAllowed("catalog", "alice", 1, 1, 1);
        RateLimiter.Response differentKey = rateLimiter.isAllowed("orders", "bob", 1, 1, 1);

        assertThat(differentRoute.isAllowed()).isTrue();
        assertThat(differentKey.isAllowed()).isTrue();
    }

    @Test
    void removesStaleEntriesDuringCleanup() {
        MutableClock clock = new MutableClock(Instant.parse("2026-03-10T00:00:00Z"));
        LocalTokenBucketRateLimiter rateLimiter = new LocalTokenBucketRateLimiter(
                clock, Duration.ofSeconds(5), Duration.ofSeconds(1));

        rateLimiter.isAllowed("orders", "alice", 1, 1, 1);
        assertThat(rateLimiter.bucketCount()).isEqualTo(1);

        clock.advance(Duration.ofSeconds(6));
        rateLimiter.isAllowed("orders", "bob", 1, 1, 1);

        assertThat(rateLimiter.bucketCount()).isEqualTo(1);
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
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
            return now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

    }
}
