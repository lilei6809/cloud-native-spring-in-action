package com.polarbookshop.edgeserver.ratelimit;

import java.util.HashMap;
import java.util.Map;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.ratelimit.AbstractRateLimiter;
import org.springframework.cloud.gateway.route.RouteDefinitionRouteLocator;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.util.Assert;

import com.polarbookshop.edgeserver.config.RateLimiterFallbackProperties;

import reactor.core.publisher.Mono;

/**
 * A resilient rate limiter for Spring Cloud Gateway.
 *
 * <p>Execution strategy:
 * 1) Prefer Redis-based distributed rate limiting for cross-instance consistency.
 * 2) If Redis errors or times out, fallback to local in-memory token bucket.
 * 3) If both Redis and local fallback fail, fail-open (allow request) to protect availability.
 *
 * <p>The class also emits Micrometer metrics and attaches rate limit diagnostic headers so
 * operators and clients can identify which decision path was used.
 */
public class ResilientGatewayRateLimiter extends AbstractRateLimiter<ResilientGatewayRateLimiter.Config> {

    private static final String CONFIGURATION_PROPERTY_NAME = "redis-rate-limiter";
    private static final String REMAINING_HEADER = "X-RateLimit-Remaining";
    private static final String REPLENISH_RATE_HEADER = "X-RateLimit-Replenish-Rate";
    private static final String BURST_CAPACITY_HEADER = "X-RateLimit-Burst-Capacity";
    private static final String REQUESTED_TOKENS_HEADER = "X-RateLimit-Requested-Tokens";

    private static final Logger log = LoggerFactory.getLogger(ResilientGatewayRateLimiter.class);

    private final RateLimiterFallbackProperties properties;
    private final LocalFallbackRunner localFallbackRunner;
    private final RedisRateLimitRunner redisRateLimitRunner;
    private final Counter redisSuccessCounter;
    private final Counter redisFallbackCounter;
    private final Counter localAllowedCounter;
    private final Counter localDeniedCounter;

    /**
     * Convenience constructor used mainly in tests or simplified wiring.
     */
    public ResilientGatewayRateLimiter(
            RateLimiterFallbackProperties properties,
            LocalFallbackRunner localFallbackRunner,
            MeterRegistry meterRegistry,
            RedisRateLimitRunner redisRateLimitRunner
    ) {
        this(properties, localFallbackRunner, meterRegistry, redisRateLimitRunner, null);
    }

    public ResilientGatewayRateLimiter(
            RateLimiterFallbackProperties properties,
            LocalFallbackRunner localFallbackRunner,
            MeterRegistry meterRegistry,
            RedisRateLimitRunner redisRateLimitRunner,
            ConfigurationService configurationService
    ) {
        // Delegate "redis-rate-limiter.*" configuration binding/lookup to AbstractRateLimiter.
        // This allows route-level and default-filters-level configs to be resolved consistently.
        super(Config.class, CONFIGURATION_PROPERTY_NAME, configurationService);
        this.properties = properties;
        this.localFallbackRunner = localFallbackRunner;
        this.redisRateLimitRunner = redisRateLimitRunner;
        // Metrics are intentionally split by path to make fallback behavior observable.
        this.redisSuccessCounter = meterRegistry.counter("gateway.ratelimiter.redis.success");
        this.redisFallbackCounter = meterRegistry.counter("gateway.ratelimiter.redis.fallback");
        this.localAllowedCounter = meterRegistry.counter("gateway.ratelimiter.local.allowed");
        this.localDeniedCounter = meterRegistry.counter("gateway.ratelimiter.local.denied");
    }

    @Override
    public Mono<Response> isAllowed(String routeId, String id) {
        // Resolve the effective token bucket config for this route (or default-filters fallback).
        Config config = loadConfiguration(routeId);
        return redisRateLimitRunner.run(routeId, id, config)
                // Redis timeout should trigger the same fallback behavior as Redis exceptions.
                .timeout(properties.getRedisTimeout())
                .map(response -> {
                    redisSuccessCounter.increment();
                    // Mark decision source for troubleshooting/observability.
                    return withMode(response, config, "redis");
                })
                // Any Redis path failure enters local fallback path.
                .onErrorResume(error -> fallbackToLocal(routeId, id, config));
    }

    Config loadConfiguration(String routeId) {
        Config routeConfig = getConfig().get(routeId);
        if (routeConfig == null) {
            // If route-specific config is missing, fallback to gateway default-filters config.
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
            // Local fallback disabled: fail-open to keep traffic flowing during Redis incidents.
            return Mono.just(withMode(new Response(true, defaultHeaders(config, -1L)), config, "local-fallback"));
        }
        try {
            Response response = localFallbackRunner.run(routeId, id, config);
            if (response.isAllowed()) {
                localAllowedCounter.increment();
            }
            else {
                localDeniedCounter.increment();
            }
            return Mono.just(withMode(response, config, "local-fallback"));
        }
        catch (Exception localError) {
            // Last line of defense: if local limiter itself fails, still allow request (fail-open).
            localAllowedCounter.increment();
            log.error("Local rate limiter fallback failed for routeId={} key={}, allowing request", routeId, id, localError);
            return Mono.just(withMode(new Response(true, defaultHeaders(config, -1L)), config, "local-fallback"));
        }
    }

    private Response withMode(Response response, Config config, String mode) {
        // Start from default headers so caller always receives full rate-limit metadata.
        Map<String, String> headers = new HashMap<>(defaultHeaders(config, -1L));
        // Let runner-provided headers override defaults (e.g., real remaining tokens from Redis).
        headers.putAll(response.getHeaders());
        // Expose which path decided the result: redis / local-fallback.
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


    /**
     * 为什么要单独定义成这两个接口，而不是直接把逻辑写死在类里？
     *   1. 解耦
     *      ResilientGatewayRateLimiter 只负责“切换决策”，不负责 Redis 细节和本地限流细节。
     *   2. 方便注入
     *      后面你可以把它们用 lambda 传进构造器：
     *   - 一个 lambda 调 Redis
     *   - 一个 lambda 调 Resilience4j
     *
     *   你可以把它们理解成两个“可插拔策略点”：
     *
     *   ResilientGatewayRateLimiter
     *      |-> RedisRateLimitRunner
     *      |-> LocalFallbackRunner
     *   一句话说就是：
     *   这两个接口不是业务对象，而是给限流器预留的两个扩展插槽。
     */
    @FunctionalInterface
    public interface RedisRateLimitRunner {
        /**
         * Executes the primary distributed rate-limit check.
         */
        Mono<Response> run(String routeId, String key, Config config);
    }

    @FunctionalInterface
    public interface LocalFallbackRunner {
        /**
         * Executes fallback local rate-limit check.
         */
        Response run(String routeId, String key, Config config);
    }

    /**
     * Token bucket parameters compatible with Spring Cloud Gateway's redis-rate-limiter args.
     */
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
            // Keep burst capacity at least as large as replenish rate to avoid invalid token bucket state.
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
