package com.polarbookshop.edgeserver.config;

import java.time.Clock;
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
import com.polarbookshop.edgeserver.ratelimit.RedisScriptRateLimitRunner;
import com.polarbookshop.edgeserver.ratelimit.Resilience4jLocalFallbackRunner;
import com.polarbookshop.edgeserver.ratelimit.ResilientGatewayRateLimiter;

/**
 * 限流相关 Bean 的统一装配入口。
 *
 * <p>这个类的核心价值不是“声明几个 Bean”这么简单，而是把 3 层东西接起来：
 *
 * <pre>
 * Spring Cloud Gateway RequestRateLimiter
 *     -> ResilientGatewayRateLimiter   网关适配器 / 总调度器
 *         -> RedisScriptRateLimitRunner Redis 主路径
 *         -> Resilience4jLocalFallbackRunner 本地降级路径
 * </pre>
 *
 * <p>也就是说，这个配置类负责回答一个很关键的问题：
 * “当 Gateway 需要做限流判决时，最终到底会调用哪套实现？”
 *
 * <p>当前设计是：
 * 1. 正常情况优先走 Redis 分布式限流；
 * 2. Redis 故障时切到 Resilience4j 本地限流；
 * 3. LocalTokenBucketRateLimiter 继续保留，但不再作为当前默认网关限流链路的一部分。
 */
@Configuration
@EnableConfigurationProperties(RateLimiterFallbackProperties.class)
public class RateLimiterConfiguration {

    @Bean
    Clock rateLimiterClock() {
        // 单独声明 Clock 的目的，是把“当前时间”也变成可注入依赖。
        // 这样将来如果某个本地限流实现需要依赖时间推进，
        // 就可以在测试里替换成可控时钟，而不是把 System.currentTimeMillis() 写死。
        return Clock.systemUTC();
    }

    @Bean
    LocalTokenBucketRateLimiter localTokenBucketRateLimiter(
            Clock rateLimiterClock,
            RateLimiterFallbackProperties properties
    ) {
        // 这个 Bean 现在继续保留，原因有两个：
        // 1. 它仍然是项目里一个独立、可测试的本地 token bucket 实现；
        // 2. 即使当前默认网关 fallback 已经切到 Resilience4j，
        //    保留它也方便后续比较、回退或做实验。
        //
        // 注意：保留 Bean 不等于当前网关主链路一定会用到它。
        // 真正默认使用谁，要看下面 @Primary 的 RateLimiter<?> Bean。
        return new LocalTokenBucketRateLimiter(
                rateLimiterClock,
                properties.getEntryTtl(),
                properties.getCleanupInterval());
    }

    @Primary
    @Bean
    RateLimiter<?> resilientGatewayRateLimiter(
            RateLimiterFallbackProperties properties,
            MeterRegistry meterRegistry,
            ReactiveStringRedisTemplate redisTemplate,
            @Qualifier(RedisRateLimiter.REDIS_SCRIPT_NAME) RedisScript<List<Long>> redisScript,
            ConfigurationService configurationService
    ) {
        // 这是整个配置类里最关键的 Bean。
        //
        // 为什么返回类型写成 RateLimiter<?>，而不是具体类？
        // 因为 Spring Cloud Gateway 在自动配置时按“RateLimiter 类型”找默认实现。
        // 只要这里是 @Primary，Gateway 默认就会注入这一个。
        //
        // 为什么一定要 @Primary？
        // 因为容器里可能同时存在多种限流相关 Bean。
        // 如果不明确指定“默认 RateLimiter 是谁”，
        // Gateway 的自动装配阶段就可能再次出现“按类型注入冲突”的问题。
        //
        // 这里真正完成的是一条装配链：
        // - properties：控制 Redis 超时、fallback 行为、mode header 等
        // - meterRegistry：记录 redis success / fallback / local allow / deny 指标
        // - redisTemplate + redisScript：给 Redis runner 使用
        // - configurationService：让 ResilientGatewayRateLimiter 接入 Gateway 的 route 配置绑定
        //
        // 最终效果是：
        // Gateway 调用 RequestRateLimiter 时，实际拿到的是新的 ResilientGatewayRateLimiter，
        // 而不是旧的 ResilientRedisRateLimiter。
        return new ResilientGatewayRateLimiter(
                properties,

                new Resilience4jLocalFallbackRunner(),
                meterRegistry,

                new RedisScriptRateLimitRunner(redisTemplate, redisScript),
                // ConfigurationService 是把当前 limiter 接进 Gateway 配置体系的关键桥梁。
                // 没有它，routeId -> Config 的绑定链就很难和 RequestRateLimiter 对齐。
                configurationService);
    }
}
