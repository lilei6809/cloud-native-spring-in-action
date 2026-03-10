package com.polarbookshop.edgeserver.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;

import com.polarbookshop.edgeserver.config.RateLimiterFallbackProperties;

import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class ResilientRedisRateLimiterTest {

    private static final String MODE_HEADER = "X-RateLimit-Mode";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-03-10T00:00:00Z"));
    private final RateLimiterFallbackProperties properties = new RateLimiterFallbackProperties();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private ResilientRedisRateLimiter.LocalRateLimitRunner localRateLimiter;

    @BeforeEach
    void setUp() {
        properties.setModeHeaderName(MODE_HEADER);
        properties.setEntryTtl(Duration.ofMinutes(5));
        properties.setCleanupInterval(Duration.ofSeconds(30));
        properties.setRedisTimeout(Duration.ofMillis(250));
        LocalTokenBucketRateLimiter delegate = new LocalTokenBucketRateLimiter(
                clock, properties.getEntryTtl(), properties.getCleanupInterval());
        localRateLimiter = (routeId, key, config) -> delegate
                .isAllowed(routeId, key, config.getReplenishRate(), config.getBurstCapacity(), config.getRequestedTokens());
    }

    @Test
    void usesRedisResponseWhenRedisSucceeds() {
        ResilientRedisRateLimiter rateLimiter = newRateLimiter(
                (routeId, key, config) -> Mono.just(new RateLimiter.Response(true, Map.of("X-RateLimit-Remaining", "9"))));
        rateLimiter.getConfig().put("orders", config(5, 10, 1));

        RateLimiter.Response response = rateLimiter.isAllowed("orders", "alice").block();

        assertThat(response).isNotNull();
        assertThat(response.isAllowed()).isTrue();
        assertThat(response.getHeaders()).containsEntry(MODE_HEADER, "redis");
        assertThat(response.getHeaders()).containsEntry("X-RateLimit-Remaining", "9");
    }

    @Test
    void fallsBackToLocalRateLimiterWhenRedisFails() {
        ResilientRedisRateLimiter rateLimiter = newRateLimiter(
                (routeId, key, config) -> Mono.error(new IllegalStateException("redis unavailable")));
        rateLimiter.getConfig().put("orders", config(1, 2, 1));

        RateLimiter.Response first = rateLimiter.isAllowed("orders", "alice").block();
        RateLimiter.Response second = rateLimiter.isAllowed("orders", "alice").block();
        RateLimiter.Response third = rateLimiter.isAllowed("orders", "alice").block();

        assertThat(first).isNotNull();
        assertThat(first.isAllowed()).isTrue();
        assertThat(second).isNotNull();
        assertThat(second.isAllowed()).isTrue();
        assertThat(third).isNotNull();
        assertThat(third.isAllowed()).isFalse();
        assertThat(third.getHeaders()).containsEntry(MODE_HEADER, "local-fallback");
    }

    @ExtendWith(OutputCaptureExtension.class)
    @Test
    void logsWhenRedisFallbackIsActivated(CapturedOutput output) {
        ResilientRedisRateLimiter rateLimiter = newRateLimiter(
                (routeId, key, config) -> Mono.error(new IllegalStateException("redis unavailable")));
        rateLimiter.getConfig().put("orders", config(1, 1, 1));

        RateLimiter.Response response = rateLimiter.isAllowed("orders", "alice").block();

        assertThat(response).isNotNull();
        assertThat(output.getOut()).contains("Falling back to local rate limiter");
        assertThat(meterRegistry.get("gateway.ratelimiter.redis.fallback").counter().count()).isEqualTo(1.0);
    }

    @Test
    void allowsRequestWhenRedisAndLocalFallbackBothFail() {
        ResilientRedisRateLimiter rateLimiter = new ResilientRedisRateLimiter(
                properties,
                (routeId, key, config) -> {
                    throw new IllegalStateException("local failure");
                },
                meterRegistry,
                (routeId, key, config) -> Mono.error(new IllegalStateException("redis unavailable")));
        rateLimiter.getConfig().put("orders", config(1, 1, 1));

        RateLimiter.Response response = rateLimiter.isAllowed("orders", "alice").block();

        assertThat(response).isNotNull();
        assertThat(response.isAllowed()).isTrue();
        assertThat(response.getHeaders()).containsEntry(MODE_HEADER, "local-fallback");
    }

    private ResilientRedisRateLimiter newRateLimiter(ResilientRedisRateLimiter.RedisRateLimitRunner redisRunner) {
        return new ResilientRedisRateLimiter(properties, localRateLimiter, meterRegistry, redisRunner);
    }

    private ResilientRedisRateLimiter.Config config(int replenishRate, long burstCapacity, int requestedTokens) {
        return new ResilientRedisRateLimiter.Config()
                .setReplenishRate(replenishRate)
                .setBurstCapacity(burstCapacity)
                .setRequestedTokens(requestedTokens);
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

    }
}
