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
        // 使用与生产配置一致的 mode header 名称，确保断言验证的就是实际运行时行为。
        RateLimiterFallbackProperties properties = new RateLimiterFallbackProperties();
        properties.setModeHeaderName(MODE_HEADER);

        // 给适配器注入：
        // 1) 一个简单的本地 fallback 实现（本测试里不会走到）
        // 2) 一个始终成功的 Redis runner，并返回伪造的剩余令牌头
        // 这样测试就只关注“Redis 成功路径”的行为。
        ResilientGatewayRateLimiter rateLimiter = new ResilientGatewayRateLimiter(
                properties,
                (routeId, key, config) -> new RateLimiter.Response(true, Map.of("X-RateLimit-Remaining", "9")),
                new SimpleMeterRegistry(),
                (routeId, key, config) -> Mono.just(new RateLimiter.Response(true, Map.of("X-RateLimit-Remaining", "9"))));
        // 模拟 AbstractRateLimiter 在真实网关启动时按 routeId 注入的限流配置。
        // 运行时适配器会根据 routeId 去配置表里取这一组参数。
        rateLimiter.getConfig().put("orders", new ResilientGatewayRateLimiter.Config()
                .setReplenishRate(5)
                .setBurstCapacity(10)
                .setRequestedTokens(1));

        // 触发一次“orders 路由 + alice 限流 key”的限流判定。
        RateLimiter.Response response = rateLimiter.isAllowed("orders", "alice").block();

        // Redis 正常时应满足：
        // 1) 请求被允许
        // 2) Redis 返回的头信息被保留
        // 3) 决策来源被标记为 redis
        assertThat(response).isNotNull();
        assertThat(response.isAllowed()).isTrue();
        assertThat(response.getHeaders()).containsEntry(MODE_HEADER, "redis");
        assertThat(response.getHeaders()).containsEntry("X-RateLimit-Remaining", "9");
    }

    @Test
    void fallsBackToLocalLimiterWhenRedisFails() {
        // 保持 mode header 与生产配置一致，便于验证 Redis 故障后到底是谁做出了判决。
        RateLimiterFallbackProperties properties = new RateLimiterFallbackProperties();
        properties.setModeHeaderName(MODE_HEADER);

        // 这里让 Redis runner 恒定失败，从而强制 ResilientGatewayRateLimiter 进入本地 fallback 分支。
        // 本地 fallback 期望由后续要实现的 Resilience4j runner 承担，它应该维护每实例本地限流状态。
        ResilientGatewayRateLimiter rateLimiter = new ResilientGatewayRateLimiter(
                properties,
                new Resilience4jLocalFallbackRunner(),
                new SimpleMeterRegistry(),
                (routeId, key, config) -> Mono.error(new IllegalStateException("redis unavailable")));
        // 配一个很小的桶，证明重复 fallback 判定会共享同一个本地状态：
        // burstCapacity=2 时，第 3 次必须被拒绝。
        rateLimiter.getConfig().put("orders", new ResilientGatewayRateLimiter.Config()
                .setReplenishRate(1)
                .setBurstCapacity(2)
                .setRequestedTokens(1));

        // 对同一个 routeId + key 连续做 3 次判定，应该消耗同一个本地桶。
        RateLimiter.Response first = rateLimiter.isAllowed("orders", "alice").block();
        RateLimiter.Response second = rateLimiter.isAllowed("orders", "alice").block();
        RateLimiter.Response third = rateLimiter.isAllowed("orders", "alice").block();

        // 前两次仍在 burst 容量内，第 3 次超限。
        // 同时响应头必须表明，这次判决来自 local-fallback，而不是 redis。
        assertThat(first).isNotNull();
        assertThat(first.isAllowed()).isTrue();
        assertThat(second).isNotNull();
        assertThat(second.isAllowed()).isTrue();
        assertThat(third).isNotNull();
        assertThat(third.isAllowed()).isFalse();
        assertThat(third.getHeaders()).containsEntry(MODE_HEADER, "local-fallback");
    }


}
