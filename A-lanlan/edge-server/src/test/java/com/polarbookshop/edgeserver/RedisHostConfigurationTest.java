package com.polarbookshop.edgeserver;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisHostConfigurationTest {

    @Test
    void applicationDefaultRedisHostUsesBareHostname() throws IOException {
        String applicationYaml = Files.readString(Path.of("src/main/resources/application.yaml"));

        assertTrue(applicationYaml.contains("host: ${DATA_REDIS_HOST:localhost}"));
        assertFalse(applicationYaml.contains("host: ${DATA_REDIS_HOST:http://localhost}"));
    }

    @Test
    void kubernetesRedisHostUsesBareHostname() throws IOException {
        String deploymentYaml = Files.readString(Path.of("k8s/deployment.yaml"));

        assertTrue(deploymentYaml.contains("value: polar-redis"));
        assertFalse(deploymentYaml.contains("value: http://polar-redis"));
    }
}
