package com.example.batch.worker.imports.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "batch.worker.import.skip")
public record ImportSkipProperties(
        boolean enabled,
        String thresholdMode,
        int maxSkipCount,
        double maxSkipRate,
        String skipErrorCodes,
        String skipAction,
        String errorSinkType,
        int errorOutputRetentionDays
) {
    public boolean enabledByDefault() {
        return enabled;
    }
}
