package com.example.batch.common.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "batch.security.kms")
public class BatchKmsProperties {

    private String defaultKeyRef = "DEFAULT_TEST";
    private Map<String, String> keys = new LinkedHashMap<>();

    public String getDefaultKeyRef() {
        return defaultKeyRef;
    }

    public void setDefaultKeyRef(String defaultKeyRef) {
        this.defaultKeyRef = defaultKeyRef;
    }

    public Map<String, String> getKeys() {
        return keys;
    }

    public void setKeys(Map<String, String> keys) {
        this.keys = keys == null ? new LinkedHashMap<>() : new LinkedHashMap<>(keys);
    }
}
