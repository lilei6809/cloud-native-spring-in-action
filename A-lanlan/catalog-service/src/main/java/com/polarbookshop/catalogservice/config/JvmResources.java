package com.polarbookshop.catalogservice.config;

import lombok.Data;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jvm-resource")
@Data
public class JvmResources {
    private String cpuRequest;
    private String cpuLimit;
    private String memRequest;
    private String memLimit;
}
