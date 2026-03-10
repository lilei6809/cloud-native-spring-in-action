package com.polarbookshop.edgeserver.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;

import static org.assertj.core.api.Assertions.assertThat;

class Resilience4jLocalFallbackRunnerTest {

    @Test
    void deniesRequestAfterBurstCapacityIsExhausted() {
        // 这里直接测试未来的 Resilience4j 本地 fallback runner：
        // 在同一个 routeId + key 下，超过 burstCapacity 后应该拒绝请求。
        Resilience4jLocalFallbackRunner runner = new Resilience4jLocalFallbackRunner();
        ResilientGatewayRateLimiter.Config config = new ResilientGatewayRateLimiter.Config()
                .setReplenishRate(1)
                .setBurstCapacity(2)
                .setRequestedTokens(1);

        // 连续 3 次请求命中同一个本地桶，前 2 次允许，第 3 次超限。
        RateLimiter.Response first = runner.run("orders", "alice", config);
        RateLimiter.Response second = runner.run("orders", "alice", config);
        RateLimiter.Response third = runner.run("orders", "alice", config);

        assertThat(first.isAllowed()).isTrue();
        assertThat(second.isAllowed()).isTrue();
        assertThat(third.isAllowed()).isFalse();
    }

    @Test
    void isolatesStateByRouteAndKey() {
        // 本地 fallback 的限流状态必须按 routeId + key 隔离。
        // 否则不同路由或不同用户会互相污染限流计数。
        Resilience4jLocalFallbackRunner runner = new Resilience4jLocalFallbackRunner();
        ResilientGatewayRateLimiter.Config config = new ResilientGatewayRateLimiter.Config()
                .setReplenishRate(1)
                .setBurstCapacity(1)
                .setRequestedTokens(1);

        // 这 4 次调用中，只有最后一次与第一次命中的是同一个 routeId + key。
        // 其余 2 次应该被视为不同桶，不受第一次请求的影响。
        RateLimiter.Response ordersAlice = runner.run("orders", "alice", config);
        RateLimiter.Response ordersBob = runner.run("orders", "bob", config);
        RateLimiter.Response catalogAlice = runner.run("catalog", "alice", config);
        RateLimiter.Response ordersAliceSecond = runner.run("orders", "alice", config);

        assertThat(ordersAlice.isAllowed()).isTrue();
        assertThat(ordersBob.isAllowed()).isTrue();
        assertThat(catalogAlice.isAllowed()).isTrue();
        assertThat(ordersAliceSecond.isAllowed()).isFalse();
    }
}
