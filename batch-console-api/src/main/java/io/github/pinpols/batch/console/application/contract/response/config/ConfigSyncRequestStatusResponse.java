package io.github.pinpols.batch.console.application.contract.response.config;

/** 原请求对应的同步审计；未找到仅表示尚未观察到，不能据此推断未执行。 */
public record ConfigSyncRequestStatusResponse(boolean found, ConfigSyncLogResponse audit) {}
