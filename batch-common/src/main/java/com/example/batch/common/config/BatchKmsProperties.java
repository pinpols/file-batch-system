package com.example.batch.common.config;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@ConfigurationProperties(prefix = "batch.security.kms")
public class BatchKmsProperties {

    private String defaultKeyRef = "DEFAULT_TEST";
    private Map<String, String> keys = new LinkedHashMap<>();

    public void setKeys(Map<String, String> keys) {
        this.keys = keys == null ? new LinkedHashMap<>() : new LinkedHashMap<>(keys);
    }
}
