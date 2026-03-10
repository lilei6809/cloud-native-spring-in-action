package com.polarbookshop.edgeserver.ratelimit;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter.Response;

/**
 * Redis 故障时的每实例本地限流实现。
 *
 * <p>这里刻意选择 Resilience4j 的本地 RateLimiter 作为 fallback 引擎，
// * 并将等待时间设为 0，保证网关超限时立即拒绝，而不是在网关层排队等待许可。
// *
 * <p>为什么这里单独抽一个 runner，而不是把逻辑直接写回 ResilientGatewayRateLimiter？
 * 因为上层适配器只应该负责“Redis 失败后切不切换、最终把结果返回给 Gateway”；
 * 至于“本地如何限流”，属于另一层职责。这样拆开后：
 * 1. 网关适配器只关心流程编排；
 * 2. 当前类只关心每实例内存限流；
 * 3. 后面如果想把 fallback 引擎从 Resilience4j 换成别的实现，改动面会很小。
 *
 * <p>当前类的运行语义可以简化成：
 * 1. 用 routeId + key 组装出一个“本地桶标识”；
 * 2. 找到这个标识对应的 Resilience4j RateLimiter；
 * 3. 如果还没有，就按当前路由配置新建一个；
 * 4. 立即尝试扣减 requestedTokens 个许可；
 * 5. 返回 Gateway 能理解的 allowed / denied 结果。
 */
public class Resilience4jLocalFallbackRunner implements ResilientGatewayRateLimiter.LocalFallbackRunner {

    private static final String REMAINING_HEADER = "X-RateLimit-Remaining";
    // 这里先固定为 1 秒刷新一次本地许可。
    // 原因是当前最小实现只需要满足“每秒固定额度”的 fallback 语义，
    // 先不把刷新周期也暴露成配置，避免在教学阶段把复杂度拉高。
    private static final Duration REFRESH_PERIOD = Duration.ofSeconds(1);

    // 本地 fallback 的状态必须保存在当前实例内存里。
    // key 采用 routeId + ":" + clientKey 的形式，含义是：
    // - 同一用户访问不同路由，应分别计数；
    // - 不同用户访问同一路由，也应分别计数。
    // 这正是“降级模式下的每实例限流”最核心的隔离维度。
    private final ConcurrentMap<String, LimiterHolder> limiters = new ConcurrentHashMap<>();

    @Override
    public Response run(
            String routeId,
            String key,
            ResilientGatewayRateLimiter.Config config
    ) {
        // 为什么要把 routeId 拼进去？
        // 因为 Gateway 的限流通常不是单纯按“用户”限，
        // 而是按“某条路由上的某个用户”限。
        // 这样 order-service 和 catalog-service 即使使用同一个用户 key，
        // 也不会互相污染额度。
        String limiterKey = routeId + ":" + key;

        // compute(...) 的作用是“原子地获取或创建 limiter”。
        // 这样并发请求同时到来时，不会因为竞态条件创建出多个不同的本地桶。
        //
        // 这里还有一个非常重要的细节：
        // 如果这条 route 的限流配置后来变了（比如 burstCapacity 从 2 改到 5），
        // 旧 limiter 就不再适用。此时我们会根据 ConfigSnapshot 判断配置是否一致：
        // - 一致：复用原 limiter，继续累计状态
        // - 不一致：丢弃旧 limiter，按新配置重新创建
        LimiterHolder holder = limiters.compute(limiterKey, (ignored, existing) -> {
            if (existing != null && existing.matches(config)) {
                return existing;
            }
            return new LimiterHolder(newLimiter(limiterKey, config), snapshot(config));
        });

        // requestedTokens 表示这次请求要消耗多少许可。
        // 大多数场景下它是 1，但保留这个参数能和 Gateway 原有的
        // redis-rate-limiter.requestedTokens 语义保持一致。
        //
        // 这里使用 acquirePermission(...) 而不是等待式 API，
        // 因为网关层更适合“立刻判决”，而不是为了等一个许可把请求挂住。
        boolean allowed = holder.rateLimiter.acquirePermission(config.getRequestedTokens());

        // Resilience4j 内部的 availablePermissions 可能出现负值语义，
        // 这里统一向上截断到 0，避免把过于底层的实现细节直接暴露给 Gateway 响应头。
        long remaining = Math.max(holder.rateLimiter.getMetrics().getAvailablePermissions(), 0);

        // 这里只返回最基础的 remaining header。
        // 更完整的限流响应头（如 replenishRate / burstCapacity / requestedTokens / mode）
        // 会在 ResilientGatewayRateLimiter 里统一补齐，
        // 这样当前类只负责“本地判决”，不负责“网关响应拼装”。
        return new org.springframework.cloud.gateway.filter.ratelimit.RateLimiter.Response(
                allowed,
                Map.of(REMAINING_HEADER, String.valueOf(remaining)));
    }

    private RateLimiter newLimiter(String limiterKey, ResilientGatewayRateLimiter.Config config) {
        // 这里是在把 Gateway 的 Config 翻译成 Resilience4j 的本地限流配置。
        //
        // 当前最小映射关系是：
        // - burstCapacity -> limitForPeriod
        //   含义：一个刷新周期内最多允许多少个许可
        // - timeoutDuration = 0
        //   含义：如果本周期许可耗尽，就立即拒绝，不等待
        //
        // 为什么暂时没有把 replenishRate 精确映射进去？
        // 因为 Resilience4j 和 RedisRateLimiter 的令牌桶模型并不是一比一等价。
        // 作为 Redis 故障下的 degraded mode，我们当前先追求“简单、稳定、立即拒绝”的语义，
        // 而不是追求和分布式 Redis 版本完全数学等价。
        RateLimiterConfig rateLimiterConfig = RateLimiterConfig.custom()
                .limitRefreshPeriod(REFRESH_PERIOD)
                .limitForPeriod(Math.toIntExact(config.getBurstCapacity()))
                .timeoutDuration(Duration.ZERO)
                .build();

        // 为什么每次 newLimiter 都新建一个 RateLimiterRegistry？
        // 因为这里的目标只是按 key 构造一个独立 limiter，当前不需要共享 registry 的高级能力。
        // 这样实现最直接，也更容易教学和测试。
        return RateLimiterRegistry.of(rateLimiterConfig).rateLimiter(limiterKey);
    }

    private ConfigSnapshot snapshot(ResilientGatewayRateLimiter.Config config) {
        // ConfigSnapshot 的作用是记录“创建这个 limiter 时使用的是哪组配置”。
        // 以后再命中同一个 routeId + key 时，就能判断当前配置有没有变。
        return new ConfigSnapshot(
                config.getBurstCapacity(),
                config.getRequestedTokens(),
                config.getReplenishRate());
    }

    // record 很适合这种“只用于值比较”的小型不可变对象。
    // 这里不需要行为，只需要稳定保存配置快照并支持 equals/hashCode。
    private record ConfigSnapshot(long burstCapacity, int requestedTokens, int replenishRate) {
    }

    private record LimiterHolder(RateLimiter rateLimiter, ConfigSnapshot snapshot) {
        private boolean matches(ResilientGatewayRateLimiter.Config config) {
            // matches(...) 的语义不是“业务上配置相似就算一样”，
            // 而是“这 3 个关键字段完全一致才允许复用旧 limiter”。
            // 这样能避免配置变化后仍错误复用旧状态。
            return snapshot.equals(new ConfigSnapshot(
                    config.getBurstCapacity(),
                    config.getRequestedTokens(),
                    config.getReplenishRate()));
        }
    }
}
