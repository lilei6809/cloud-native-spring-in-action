package com.polarbookshop.edgeserver.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 *  redis script rate limiter 的配置类
 */
@ConfigurationProperties("polar.gateway.rate-limiter")
public class RateLimiterFallbackProperties {

    private boolean localFallbackEnabled = true;
    private Duration entryTtl = Duration.ofMinutes(10);
    private Duration cleanupInterval = Duration.ofMinutes(1);
    private Duration redisTimeout = Duration.ofMillis(250);
    private String modeHeaderName = "X-RateLimit-Mode";

    public boolean isLocalFallbackEnabled() {
        return localFallbackEnabled;
    }

    public void setLocalFallbackEnabled(boolean localFallbackEnabled) {
        this.localFallbackEnabled = localFallbackEnabled;
    }

    public Duration getEntryTtl() {
        return entryTtl;
    }

    public void setEntryTtl(Duration entryTtl) {
        this.entryTtl = entryTtl;
    }

    public Duration getCleanupInterval() {
        return cleanupInterval;
    }

    public void setCleanupInterval(Duration cleanupInterval) {
        this.cleanupInterval = cleanupInterval;
    }

    public Duration getRedisTimeout() {
        return redisTimeout;
    }

    public void setRedisTimeout(Duration redisTimeout) {
        this.redisTimeout = redisTimeout;
    }

    public String getModeHeaderName() {
        return modeHeaderName;
    }

    public void setModeHeaderName(String modeHeaderName) {
        this.modeHeaderName = modeHeaderName;
    }
}
