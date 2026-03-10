package com.polarbookshop.edgeserver.ratelimit;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import reactor.core.publisher.Mono;

/**
 * 基于 Spring Cloud Gateway 现有 Lua 脚本协议的 Redis 限流 runner。
 *
 * <p>职责保持很窄：
 * 1. 把 routeId + key + Config 翻译成 Redis keys 和 script args；
 * 2. 执行 Lua 脚本；
 * 3. 把脚本结果翻译回 Gateway 的 RateLimiter.Response。
 *
 * <p>它不负责 fallback，不吞 Redis 异常。
 * Redis 故障时的降级决策由上层 ResilientGatewayRateLimiter 统一处理。
 *
 * <p>为什么要把 Redis 这段逻辑单独抽成一个 runner？
 * 因为在整个限流链路里，Redis 这层只应该关心“如何和 Redis 交互”，
 * 而不应该同时关心：
 * 1. Redis 失败后是否降级；
 * 2. 本地 fallback 用什么实现；
 * 3. Gateway 最终返回哪些附加头。
 *
 * <p>换句话说，当前类只解决一个问题：
 * “给我 routeId、key 和限流参数，我去 Redis 问一下这次能不能放行。”
 *
 * <p>整个调用链可以理解成：
 *
 * <pre>
 * RequestRateLimiter
 *     -> ResilientGatewayRateLimiter
 *         -> RedisScriptRateLimitRunner
 *             -> Redis Lua script
 * </pre>
 *
 * <p>如果 Redis 正常返回，本类就把脚本结果翻译成 Gateway 的 Response。
 * 如果 Redis 报错，本类不做容错决策，而是把错误继续向上抛。
 * 这样职责边界才清楚。
 */
public final class RedisScriptRateLimitRunner implements ResilientGatewayRateLimiter.RedisRateLimitRunner {

    private static final String REMAINING_HEADER = "X-RateLimit-Remaining";

    private final ReactiveStringRedisTemplate redisTemplate;
    private final RedisScript<List<Long>> redisScript;

    public RedisScriptRateLimitRunner(
            ReactiveStringRedisTemplate redisTemplate,
            RedisScript<List<Long>> redisScript
    ) {
        // 这里注入的是两个“基础设施依赖”：
        // 1. ReactiveStringRedisTemplate：真正执行 Redis 命令
        // 2. RedisScript：Spring Cloud Gateway 官方 RedisRateLimiter 使用的 Lua 脚本
        //
        // 这样做的好处是：
        // - 当前类不关心 Redis 连接怎么建
        // - 也不自己维护脚本内容
        // - 只专注于“如何调用它们”
        this.redisTemplate = redisTemplate;
        this.redisScript = redisScript;
    }

    @Override
    public Mono<RateLimiter.Response> run(String routeId, String key, ResilientGatewayRateLimiter.Config config) {
        // 第一步：把“哪条路由 + 哪个限流 key”的语义，翻译成 Redis 里真正使用的两个 key。
        //
        // 这里必须带上 routeId，因为网关限流通常不是“整个系统里这个用户只有一个桶”，
        // 而是“同一个用户在不同路由上可以有不同额度”。
        //
        // 例如：
        // - orders + alice
        // - catalog + alice
        // 应该是两个不同的计数桶。
        List<String> keys = getKeys(routeId, key);

        // 第二步：把 Gateway Config 翻译成 Lua 脚本参数。
        //
        // 这 4 个参数顺序是和 Spring Cloud Gateway 内置 RedisRateLimiter 的脚本协议对齐的：
        // 1. replenishRate   每秒补充多少 token
        // 2. burstCapacity   桶最多能装多少 token
        // 3. requestedTokens 预留位置/时间占位参数（当前沿用官方写法，传空字符串）
        // 4. requestedTokens 这次请求实际要消耗多少 token
        //
        // 注意：
        // 第 3 个参数这里保留空字符串，不是随便乱写，而是为了兼容现有脚本调用约定。
        // 现阶段我们的目标不是“重新设计 Redis Lua 协议”，而是“稳定复用 Gateway 已有协议”。
        List<String> scriptArgs = Arrays.asList(
                Integer.toString(config.getReplenishRate()),
                Long.toString(config.getBurstCapacity()),
                "",
                Integer.toString(config.getRequestedTokens()));

        // 第三步：真正执行 Lua 脚本。
        //
        // execute(...) 返回的是一个 reactive 流，而不是同步值。
        // 原因是这里底层走的是 reactive redis client。
        //
        // 为什么这里不 catch 异常？
        // 因为“Redis 执行失败后怎么办”不是本类职责。
        // 本类只负责把 Redis 的“成功返回”翻译成结果；
        // 一旦 Redis 失败，就应该把异常继续交给上层 ResilientGatewayRateLimiter，
        // 由它来决定是否切本地 fallback。
        return redisTemplate.execute(redisScript, keys, scriptArgs)
                // Lua 执行结果可能是分段返回的，所以这里先把所有片段合并成一个 List<Long>。
                // 对调用者来说，我们希望拿到的是一个完整结果，而不是半截流式片段。
                .reduce(new java.util.ArrayList<Long>(), (all, part) -> {
                    all.addAll(part);
                    return all;
                })
                // 第四步：把 Lua 脚本返回值翻译成 Gateway Response。
                //
                // 当前脚本返回约定是：
                // - results[0] == 1 表示允许
                // - results[0] == 0 表示拒绝
                // - results[1] 表示剩余 token 数
                //
                // 这里非常重要的一点是：
                // “Redis 正常返回 allowed=false” 不是错误场景。
                // 这意味着用户真的超限了，此时不应该触发 fallback。
                .map(results -> new RateLimiter.Response(results.get(0) == 1L, Map.of(
                        REMAINING_HEADER, Long.toString(results.get(1))
                )))
                // 如果 Redis 没返回任何结果，这里采用和原有实现一致的 fail-open 兜底：
                // 默认允许请求继续通过，并把 remaining 标成 -1。
                //
                // 这是一个工程上的保守选择：
                // 宁可在“没有明确结果”时允许通过，也不要在这里因为空结果直接误杀请求。
                .switchIfEmpty(Mono.just(new RateLimiter.Response(true, Map.of(
                        REMAINING_HEADER, "-1"
                ))));
    }

    private List<String> getKeys(String routeId, String key) {
        // RedisRateLimiter 使用两个 key：
        // 1. tokens    当前桶里剩余多少 token
        // 2. timestamp 上次刷新/计算时间
        //
        // key 模板中的 {routeId.key} 不是装饰，而是 Redis Cluster hash tag 语法。
        // 它的作用是让一组相关 key 落到同一个 hash slot，避免 Lua 脚本跨 slot 执行失败。
        //
        // 也就是说，这里的大括号不是随便加的，而是为了兼容 Redis Cluster 的关键细节。
        String prefix = "request_rate_limiter.{" + routeId + "." + key + "}.";
        return List.of(prefix + "tokens", prefix + "timestamp");
    }
}
