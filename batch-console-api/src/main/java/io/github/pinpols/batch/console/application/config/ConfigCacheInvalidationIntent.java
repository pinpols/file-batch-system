package io.github.pinpols.batch.console.application.config;

/** 一条待投递的配置缓存失效意图。 */
public record ConfigCacheInvalidationIntent(
    Long id, String tenantId, String configType, String configCode) {}
