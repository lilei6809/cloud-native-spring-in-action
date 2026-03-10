package com.polarbookshop.edgeserver;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.filter.factory.RequestRateLimiterGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.polarbookshop.edgeserver.ratelimit.ResilientRedisRateLimiter;

import static org.assertj.core.api.Assertions.assertThat;


@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class EdgeServerApplicationTest {

    private static final int REDIS_PORT = 6379;

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(REDIS_PORT);

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private RateLimiter<?> rateLimiter;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.redis.host", redis::getHost);
        registry.add("spring.redis.port", () -> redis.getMappedPort(REDIS_PORT));
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(REDIS_PORT));

    }

    @Test
    void verifyThatSpringContextLoads() {
        assertThat(applicationContext.getBean(RequestRateLimiterGatewayFilterFactory.class)).isNotNull();
        assertThat(rateLimiter).isInstanceOf(ResilientRedisRateLimiter.class);
        assertThat(((ResilientRedisRateLimiter) rateLimiter).getConfig())
                .containsKeys("catalog-service", "order-service");
    }

}
