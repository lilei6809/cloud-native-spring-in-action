package com.polarbookshop.edgeserver.ratelimit;

import java.util.HashMap;
import java.util.Map;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.ratelimit.AbstractRateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.route.RouteDefinitionRouteLocator;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.util.Assert;

import com.polarbookshop.edgeserver.config.RateLimiterFallbackProperties;

import reactor.core.publisher.Mono;

public class ResilientRedisRateLimiter extends AbstractRateLimiter<ResilientRedisRateLimiter.Config> {

    private static final String CONFIGURATION_PROPERTY_NAME = "redis-rate-limiter";
    private static final String REMAINING_HEADER = "X-RateLimit-Remaining";
    private static final String REPLENISH_RATE_HEADER = "X-RateLimit-Replenish-Rate";
    private static final String BURST_CAPACITY_HEADER = "X-RateLimit-Burst-Capacity";
    private static final String REQUESTED_TOKENS_HEADER = "X-RateLimit-Requested-Tokens";

    private static final Logger log = LoggerFactory.getLogger(ResilientRedisRateLimiter.class);

    private final RateLimiterFallbackProperties properties;
    private final LocalRateLimitRunner localRateLimitRunner;
    private final RedisRateLimitRunner redisRateLimitRunner;
    private final Counter redisSuccessCounter;
    private final Counter redisFallbackCounter;
    private final Counter localAllowedCounter;
    private final Counter localDeniedCounter;

    public ResilientRedisRateLimiter(
            RateLimiterFallbackProperties properties,
            LocalRateLimitRunner localRateLimitRunner,
            MeterRegistry meterRegistry,
            RedisRateLimitRunner redisRateLimitRunner
    ) {
        this(properties, localRateLimitRunner, meterRegistry, redisRateLimitRunner, null);
    }

    public ResilientRedisRateLimiter(
            RateLimiterFallbackProperties properties,
            LocalRateLimitRunner localRateLimitRunner,
            MeterRegistry meterRegistry,
            RedisRateLimitRunner redisRateLimitRunner,
            ConfigurationService configurationService
    ) {
        super(Config.class, CONFIGURATION_PROPERTY_NAME, configurationService);
        this.properties = properties;
        this.localRateLimitRunner = localRateLimitRunner;
        this.redisRateLimitRunner = redisRateLimitRunner;
        this.redisSuccessCounter = meterRegistry.counter("gateway.ratelimiter.redis.success");
        this.redisFallbackCounter = meterRegistry.counter("gateway.ratelimiter.redis.fallback");
        this.localAllowedCounter = meterRegistry.counter("gateway.ratelimiter.local.allowed");
        this.localDeniedCounter = meterRegistry.counter("gateway.ratelimiter.local.denied");
    }

    @Override
    public Mono<Response> isAllowed(String routeId, String id) {
        Config config = loadConfiguration(routeId);
        return redisRateLimitRunner.run(routeId, id, config)
                .timeout(properties.getRedisTimeout())
                .map(response -> {
                    redisSuccessCounter.increment();
                    return withMode(response, config, "redis");
                })
                .onErrorResume(error -> fallbackToLocal(routeId, id, config));
    }

    Config loadConfiguration(String routeId) {
        Config routeConfig = getConfig().get(routeId);
        if (routeConfig == null) {
            routeConfig = getConfig().get(RouteDefinitionRouteLocator.DEFAULT_FILTERS);
        }
        if (routeConfig == null) {
            throw new IllegalArgumentException("No Configuration found for route " + routeId + " or defaultFilters");
        }
        return routeConfig;
    }

    private Mono<Response> fallbackToLocal(String routeId, String id, Config config) {
        redisFallbackCounter.increment();
        log.warn("Falling back to local rate limiter for routeId={} key={}", routeId, id);
        if (!properties.isLocalFallbackEnabled()) {
            return Mono.just(withMode(new Response(true, defaultHeaders(config, -1L)), config, "local-fallback"));
        }
        try {
            Response response = localRateLimitRunner.run(routeId, id, config);
            if (response.isAllowed()) {
                localAllowedCounter.increment();
            }
            else {
                localDeniedCounter.increment();
            }
            return Mono.just(withMode(response, config, "local-fallback"));
        }
        catch (Exception localError) {
            localAllowedCounter.increment();
            log.error("Local rate limiter fallback failed for routeId={} key={}, allowing request", routeId, id, localError);
            return Mono.just(withMode(new Response(true, defaultHeaders(config, -1L)), config, "local-fallback"));
        }
    }

    private Response withMode(Response response, Config config, String mode) {
        Map<String, String> headers = new HashMap<>(defaultHeaders(config, -1L));
        headers.putAll(response.getHeaders());
        headers.put(properties.getModeHeaderName(), mode);
        return new Response(response.isAllowed(), headers);
    }

    private Map<String, String> defaultHeaders(Config config, Long tokensLeft) {
        Map<String, String> headers = new HashMap<>();
        headers.put(REMAINING_HEADER, String.valueOf(tokensLeft));
        headers.put(REPLENISH_RATE_HEADER, String.valueOf(config.getReplenishRate()));
        headers.put(BURST_CAPACITY_HEADER, String.valueOf(config.getBurstCapacity()));
        headers.put(REQUESTED_TOKENS_HEADER, String.valueOf(config.getRequestedTokens()));
        return headers;
    }

    @FunctionalInterface
    public interface RedisRateLimitRunner {

        Mono<Response> run(String routeId, String key, Config config);
    }

    @FunctionalInterface
    public interface LocalRateLimitRunner {

        Response run(String routeId, String key, Config config);
    }

    public static class Config {

        private int replenishRate;
        private long burstCapacity = 1;
        private int requestedTokens = 1;

        public int getReplenishRate() {
            return replenishRate;
        }

        public Config setReplenishRate(int replenishRate) {
            this.replenishRate = replenishRate;
            return this;
        }

        public long getBurstCapacity() {
            return burstCapacity;
        }

        public Config setBurstCapacity(long burstCapacity) {
            Assert.isTrue(burstCapacity >= this.replenishRate, "BurstCapacity(" + burstCapacity
                    + ") must be greater than or equal than replenishRate(" + this.replenishRate + ")");
            this.burstCapacity = burstCapacity;
            return this;
        }

        public int getRequestedTokens() {
            return requestedTokens;
        }

        public Config setRequestedTokens(int requestedTokens) {
            this.requestedTokens = requestedTokens;
            return this;
        }
    }
}
