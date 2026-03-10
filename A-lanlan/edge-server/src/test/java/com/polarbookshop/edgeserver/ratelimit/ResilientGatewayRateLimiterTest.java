package com.polarbookshop.edgeserver.ratelimit;

import java.util.Map;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;

import com.polarbookshop.edgeserver.config.RateLimiterFallbackProperties;

import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class ResilientGatewayRateLimiterTest {

    private static final String MODE_HEADER = "X-RateLimit-Mode";

    @Test
    void usesRedisResponseWhenRedisSucceeds() {
        RateLimiterFallbackProperties properties = new RateLimiterFallbackProperties();
        properties.setModeHeaderName(MODE_HEADER);

        ResilientGatewayRateLimiter rateLimiter = new ResilientGatewayRateLimiter(
                properties,
                (routeId, key, config) -> new RateLimiter.Response(true, Map.of("X-RateLimit-Remaining", "9")),
                new SimpleMeterRegistry(),
                (routeId, key, config) -> Mono.just(new RateLimiter.Response(true, Map.of("X-RateLimit-Remaining", "9"))));
        rateLimiter.getConfig().put("orders", new ResilientGatewayRateLimiter.Config()
                .setReplenishRate(5)
                .setBurstCapacity(10)
                .setRequestedTokens(1));

        RateLimiter.Response response = rateLimiter.isAllowed("orders", "alice").block();

        assertThat(response).isNotNull();
        assertThat(response.isAllowed()).isTrue();
        assertThat(response.getHeaders()).containsEntry(MODE_HEADER, "redis");
        assertThat(response.getHeaders()).containsEntry("X-RateLimit-Remaining", "9");
    }
}
