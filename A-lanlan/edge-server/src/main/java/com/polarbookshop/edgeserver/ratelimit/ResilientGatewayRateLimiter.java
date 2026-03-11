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
 * Spring Cloud Gateway 使用的“带降级能力”的限流适配器。
 *
 * <p>先说它在系统里的位置：
 *
 * <pre>
 * HTTP 请求
 *   -> Gateway 路由匹配
 *   -> RequestRateLimiter filter
 *   -> ResilientGatewayRateLimiter.isAllowed(routeId, key)
 *      -> 优先走 RedisRateLimitRunner
 *      -> Redis 故障时切 LocalFallbackRunner
 *      -> 如果 fallback 自己也失败，则最终放行
 * </pre>
 *
 * <p>所以它不是“真正执行 Redis 命令的类”，也不是“真正实现本地限流算法的类”。
 * 它的核心职责只有一个：编排限流判决流程。
 *
 * <p>为什么要把这个适配器单独抽出来？
 * 因为网关层真正关心的是：
 * 1. 当前请求要不要放行；
 * 2. Redis 失败时是否切换；
 * 3. 返回头里如何标明这次判决来自哪条路径；
 * 4. 指标和日志如何体现当前处于正常模式还是降级模式。
 *
 * <p>一句话概括当前类：
 * “它是 Gateway 和具体限流实现之间的总调度器。”
 */
public class ResilientGatewayRateLimiter extends AbstractRateLimiter<ResilientGatewayRateLimiter.Config> {

    private static final String CONFIGURATION_PROPERTY_NAME = "redis-rate-limiter";
    private static final String REMAINING_HEADER = "X-RateLimit-Remaining";
    private static final String REPLENISH_RATE_HEADER = "X-RateLimit-Replenish-Rate";
    private static final String BURST_CAPACITY_HEADER = "X-RateLimit-Burst-Capacity";
    private static final String REQUESTED_TOKENS_HEADER = "X-RateLimit-Requested-Tokens";

    private static final Logger log = LoggerFactory.getLogger(ResilientGatewayRateLimiter.class);

    //
    private final RateLimiterFallbackProperties properties;
    private final LocalFallbackRunner localFallbackRunner;
    private final RedisRateLimitRunner redisRateLimitRunner;
    private final Counter redisSuccessCounter;
    private final Counter redisFallbackCounter;
    private final Counter localAllowedCounter;
    private final Counter localDeniedCounter;

    /**
     * 简化构造器，主要给单元测试或最小接线场景使用。
     *  这里不传 ConfigurationService, 如果传入的 ConfigurationService 绑定的是
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
        // 为什么要继承 AbstractRateLimiter 而不是直接 implements RateLimiter？
        // 因为 Gateway 已经内建了一套 routeId -> Config 的配置绑定机制。
        // 这里复用它，就能直接承接：
        // - 某条 route 自己的 RequestRateLimiter args
        // - default-filters 里的默认限流参数
        //
        // 换句话说，这行 super(...) 的意义不是“父类初始化”这么简单，
        // 而是把当前类接进 Gateway 现成的配置体系。
        super(Config.class, CONFIGURATION_PROPERTY_NAME, configurationService);
        this.properties = properties;
        this.localFallbackRunner = localFallbackRunner;
        this.redisRateLimitRunner = redisRateLimitRunner;

        // 指标按路径拆开记录，是为了让运维能区分：
        // - Redis 正常工作
        // - Redis 已经开始 fallback
        // - 本地 fallback 在允许还是在拒绝
        //
        // 否则你只知道“限流器还在返回结果”，却不知道系统其实已经处于 degraded mode。
        this.redisSuccessCounter = meterRegistry.counter("gateway.ratelimiter.redis.success");
        this.redisFallbackCounter = meterRegistry.counter("gateway.ratelimiter.redis.fallback");
        this.localAllowedCounter = meterRegistry.counter("gateway.ratelimiter.local.allowed");
        this.localDeniedCounter = meterRegistry.counter("gateway.ratelimiter.local.denied");
    }

    @Override
    public Mono<Response> isAllowed(String routeId, String id) {
        // 这是整个限流器的主入口。
        // RequestRateLimiter filter 在处理请求时，会把：
        // - routeId：当前命中的 Gateway 路由 id
        // - id：KeyResolver 解析出的限流 key（例如用户、IP、API key）
        // 传进来，让当前类做“最终判决”。
        //
        // 注意：当前方法只负责返回“允许/拒绝”的判决结果，
        // 真正把请求继续转发或返回 429 的，是 Gateway 上层 filter。
        Config config = loadConfiguration(routeId);
        return redisRateLimitRunner.run(routeId, id, config)
                // Redis 的“故障”不仅包括直接抛错，也包括慢到不可接受。
                // 对网关来说，超时和连接失败本质上都是：
                // “这条分布式限流链路当前不可靠了，应进入降级判断”。
                .timeout(properties.getRedisTimeout())
                .map(response -> {
                    // 只有 Redis 正常返回了判决结果，才记 redis.success。
                    // 这里哪怕是 allowed=false，也仍然算 Redis 成功工作，
                    // 因为“超限”是业务结果，不是系统故障。
                    redisSuccessCounter.increment();
                    return withMode(response, config, "redis");
                })
                // 只有当 Redis 链路抛异常/超时时，才进入 fallback。
                // 这就是“正常超限”和“依赖故障”之间最关键的分界线。
                .onErrorResume(error -> fallbackToLocal(routeId, id, config));
    }

    Config loadConfiguration(String routeId) {
        // 配置查找顺序：
        // 1. 先看当前 routeId 有没有专属限流配置
        // 2. 如果没有，再退回到 default-filters 的默认配置
        //
        // 这对应 Gateway 常见的两种使用方式：
        // - 某条路由单独配置 RequestRateLimiter
        // - 全局 default-filters 统一配置一套默认限流参数
        Config routeConfig = getConfig().get(routeId);
        if (routeConfig == null) {
            routeConfig = getConfig().get(RouteDefinitionRouteLocator.DEFAULT_FILTERS);
        }
        if (routeConfig == null) {
            // 这里直接抛错是合理的，因为没有配置就意味着当前类无法知道
            // 该用什么 replenishRate / burstCapacity / requestedTokens 进行判决。
            throw new IllegalArgumentException("No Configuration found for route " + routeId + " or defaultFilters");
        }
        return routeConfig;
    }


    private Mono<Response> fallbackToLocal(String routeId, String id, Config config) {
        // 进入这个方法就意味着：
        // Redis 路径已经不可用，当前请求不再走分布式限流，而进入降级模式。
        redisFallbackCounter.increment();
        log.warn("Falling back to local rate limiter for routeId={} key={}", routeId, id);
        if (!properties.isLocalFallbackEnabled()) {
            // 如果明确关闭了本地 fallback，就直接 fail-open 放行。
            // 这是一个可用性优先的决策：
            // 宁可让下游多扛一点压力，也不要让网关因为 Redis 挂掉把所有正常用户都拦住。
            return Mono.just(withMode(
                    new Response(true, defaultHeaders(config, -1L)),
                    config,
                    "local-fallback"));
        }
        try {
            // 本地 fallback runner 本身只做“本地判决”。
            // 至于是否记指标、最终响应头怎么拼、异常时是否放行，统一由当前类掌控。
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
            // 这是最后一道兜底：
            // 如果连本地 fallback 自己都异常了，说明“保护网关可用性”优先级更高。
            // 所以这里最终仍然选择 allow，而不是继续向外抛异常。
            localAllowedCounter.increment();
            log.error("Local rate limiter fallback failed for routeId={} key={}, allowing request", routeId, id, localError);
            return Mono.just(withMode(new Response(true, defaultHeaders(config, -1L)), config, "local-fallback"));
        }
    }

    private Response withMode(Response response, Config config, String mode) {
        // 这里的职责不是改写 allowed/denied 结果，而是统一补齐响应头。
        //
        // 设计上先从默认头开始，是为了保证无论 Redis 路径还是 fallback 路径，
        // 最终响应里都至少带着一套完整的限流元数据。
        Map<String, String> headers = new HashMap<>(defaultHeaders(config, -1L));
        // 如果底层 runner 已经算出了更准确的信息（比如真实的 remaining），
        // 就用 runner 的值覆盖默认值。
        headers.putAll(response.getHeaders());
        // mode header 的意义很大：
        // 它能告诉客户端和排障人员，这次判决到底来自 Redis 还是 local-fallback。
        // 否则你只知道“被限流了/没被限流”，却不知道系统当前已经降级。
        headers.put(properties.getModeHeaderName(), mode);
        return new Response(response.isAllowed(), headers);
    }

    private Map<String, String> defaultHeaders(Config config, Long tokensLeft) {
        // 这一组 header 是当前适配器给 Gateway 层提供的“最小统一元数据”。
        // 即使某条判决路径没有返回完整头信息，上层也能从这里拿到基本限流参数。
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
         * 执行主路径的分布式限流判定。
         *
         * <p>它返回 Mono<Response>，是因为 Redis 这条路径本质上是 reactive IO，
         * 需要自然接入 timeout / onErrorResume 这条反应式链路。
         */
        Mono<Response> run(String routeId, String key, Config config);
    }

    @FunctionalInterface
    public interface LocalFallbackRunner {
        /**
         * 执行本地 fallback 限流判定。
         *
         * <p>这里返回同步 Response，而不是 Mono<Response>，
         * 是因为当前 fallback 被视为“本机内快速判断”，不需要额外异步等待。
         */
        Response run(String routeId, String key, Config config);
    }

    /**
     * 与 Spring Cloud Gateway `redis-rate-limiter.*` 参数兼容的配置对象。
     *
     * <p>它虽然叫 Config，但不是通用业务配置，而是“每条 route 的限流合同”。
     * 当前 adapter 会按 routeId 找到对应 Config，再把它传给 Redis runner 或 local runner。
     */
    public static class Config {

        private int replenishRate;
        private long burstCapacity = 1;
        private int requestedTokens = 1;

        public int getReplenishRate() {
            return replenishRate;
        }

        public Config setReplenishRate(int replenishRate) {
            // replenishRate 表示单位时间内补充多少 token。
            this.replenishRate = replenishRate;
            return this;
        }

        public long getBurstCapacity() {
            return burstCapacity;
        }

        public Config setBurstCapacity(long burstCapacity) {
            // 这里做一个基本不变量检查：
            // burstCapacity 至少不能小于 replenishRate，
            // 否则桶模型在语义上就会很奇怪，甚至可能导致配置本身无效。
            Assert.isTrue(burstCapacity >= this.replenishRate, "BurstCapacity(" + burstCapacity
                    + ") must be greater than or equal than replenishRate(" + this.replenishRate + ")");
            this.burstCapacity = burstCapacity;
            return this;
        }

        public int getRequestedTokens() {
            return requestedTokens;
        }

        public Config setRequestedTokens(int requestedTokens) {
            // requestedTokens 表示一次请求要消耗多少 token。
            // 大多数普通 HTTP 请求场景里它是 1，但保留这个参数可以支持更灵活的限流语义。
            this.requestedTokens = requestedTokens;
            return this;
        }
    }
}
