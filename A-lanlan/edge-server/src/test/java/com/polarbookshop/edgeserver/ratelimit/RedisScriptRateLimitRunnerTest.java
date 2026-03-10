package com.polarbookshop.edgeserver.ratelimit;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisScriptRateLimitRunnerTest {

    @SuppressWarnings("unchecked")
    private final RedisScript<List<Long>> redisScript = mock(RedisScript.class);
    private final ReactiveStringRedisTemplate redisTemplate = mock(ReactiveStringRedisTemplate.class);

    @Test
    void mapsRedisDeniedResponseAndUsesRouteScopedKeys() {
        // 这里先把“Redis 正常执行，但判定超限”的边界钉死。
        // 这不是故障场景，因此后续 adapter 不应该把它当成 fallback 条件。
        //
        // RedisRateLimiter 的 Lua 脚本约定返回两个数字：
        // 1) 第一个值表示 allowed：1=允许，0=拒绝
        // 2) 第二个值表示 remaining：当前剩余令牌数
        when(redisTemplate.execute(
                eq(redisScript),
                eq(List.of(
                        "request_rate_limiter.{orders.alice}.tokens",
                        "request_rate_limiter.{orders.alice}.timestamp")),
                eq(List.of("10", "20", "", "1"))))
                .thenReturn(Flux.just(List.of(0L, 3L)));

        RedisScriptRateLimitRunner runner = new RedisScriptRateLimitRunner(redisTemplate, redisScript);

        // 触发一次 runner 调用，验证它会：
        // 1) 按 routeId + key 生成 Redis keys
        // 2) 把 Gateway Config 翻译成 Lua 参数
        // 3) 将 Redis 的 [0, 3] 映射成 Gateway Response(deny, remaining=3)
        RateLimiter.Response response = runner.run("orders", "alice", config(10, 20, 1)).block();

        assertThat(response).isNotNull();
        assertThat(response.isAllowed()).isFalse();
        assertThat(response.getHeaders()).containsEntry("X-RateLimit-Remaining", "3");
    }

    @Test
    void propagatesRedisExecutionErrorToCaller() {
        // 这里锁定的不是“超限”，而是“Redis 依赖故障”。
        // runner 自己不应该吞掉异常，更不应该在这里直接做 fallback；
        // 正确职责是把错误继续向上抛给 ResilientGatewayRateLimiter，
        // 再由 adapter 统一决定是否切到 local fallback。
        when(redisTemplate.execute(eq(redisScript), anyList(), anyList()))
                .thenReturn(Flux.error(new IllegalStateException("redis unavailable")));

        RedisScriptRateLimitRunner runner = new RedisScriptRateLimitRunner(redisTemplate, redisScript);

        assertThatThrownBy(() -> runner.run("orders", "alice", config(10, 20, 1)).block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("redis unavailable");
    }

    private ResilientGatewayRateLimiter.Config config(int replenishRate, long burstCapacity, int requestedTokens) {
        return new ResilientGatewayRateLimiter.Config()
                .setReplenishRate(replenishRate)
                .setBurstCapacity(burstCapacity)
                .setRequestedTokens(requestedTokens);
    }
}
