package com.polarbookshop.edgeserver.config;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import com.polarbookshop.edgeserver.ratelimit.LocalTokenBucketRateLimiter;
import com.polarbookshop.edgeserver.ratelimit.ResilientRedisRateLimiter;

import reactor.core.publisher.Mono;

@Configuration
@EnableConfigurationProperties(RateLimiterFallbackProperties.class)
public class RateLimiterConfiguration {

    @Bean
    Clock rateLimiterClock() {
        return Clock.systemUTC();
    }

    @Bean
    LocalTokenBucketRateLimiter localTokenBucketRateLimiter(
            Clock rateLimiterClock,
            RateLimiterFallbackProperties properties
    ) {
        return new LocalTokenBucketRateLimiter(
                rateLimiterClock,
                properties.getEntryTtl(),
                properties.getCleanupInterval());
    }

    @Primary
    @Bean
    RateLimiter<?> resilientRedisRateLimiter(
            RateLimiterFallbackProperties properties,
            LocalTokenBucketRateLimiter localTokenBucketRateLimiter,
            MeterRegistry meterRegistry,
            ReactiveStringRedisTemplate redisTemplate,
            @Qualifier(RedisRateLimiter.REDIS_SCRIPT_NAME) RedisScript<List<Long>> redisScript,
            ConfigurationService configurationService
    ) {
        return new ResilientRedisRateLimiter(
                properties,
                localTokenBucketRateLimiter,
                meterRegistry,
                (routeId, key, config) -> runRedis(redisTemplate, redisScript, routeId, key, config),
                configurationService);
    }

    private Mono<RateLimiter.Response> runRedis(
            ReactiveStringRedisTemplate redisTemplate,
            RedisScript<List<Long>> redisScript,
            String routeId,
            String key,
            ResilientRedisRateLimiter.Config config
    ) {
        List<String> keys = getKeys(routeId, key);
        List<String> scriptArgs = Arrays.asList(
                Integer.toString(config.getReplenishRate()),
                Long.toString(config.getBurstCapacity()),
                "",
                Integer.toString(config.getRequestedTokens()));
        return redisTemplate.execute(redisScript, keys, scriptArgs)
                .reduce(new java.util.ArrayList<Long>(), (all, part) -> {
                    all.addAll(part);
                    return all;
                })
                .map(results -> new RateLimiter.Response(results.get(0) == 1L, java.util.Map.of(
                        "X-RateLimit-Remaining", Long.toString(results.get(1))
                )))
                .switchIfEmpty(Mono.just(new RateLimiter.Response(true, java.util.Map.of(
                        "X-RateLimit-Remaining", "-1"
                ))));
    }

    private List<String> getKeys(String routeId, String key) {
        String prefix = "request_rate_limiter.{" + routeId + "." + key + "}.";
        return List.of(prefix + "tokens", prefix + "timestamp");
    }
}
