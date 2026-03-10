package com.polarbookshop.edgeserver.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;

@Configuration
public class RateLimiterKeyResolverConfig {
    // 在 application.yaml 中使用具体的 keyResolver

    // 在 Spring Cloud Gateway 中，我们通过编写 KeyResolver 来定义限流的维度。
    @Primary
    @Bean
    public KeyResolver userKeyResolver() {
        // 接收一个 ServerWebExchange, 输出一个 redis 使用的 key
        // 我们目的是根据 userId 生成 key, 但是需要使用 spring security 后实现
        // 现在固定返回 "anonymous"，所以所有请求都会共用同一个限流桶
        return exchange -> Mono.just("anonymous");
    }

    // 使用 user ip address 作为 key 来实现 rate limiting
    @Bean
    public KeyResolver ipKeyResolver() {

        return exchange -> Mono.just(
                exchange.getRequest()
                        .getRemoteAddress()
                        .getAddress()
                        .getHostAddress());
    }

    //  api接口限流
    @Bean
    public KeyResolver apiKeyResolver() {
        // 用请求的 Path 作为 Key
        return exchange -> Mono.just(
                exchange.getRequest().getPath().value()
        );
    }
}
