package io.github.pinpols.batch.console.domain.rbac.web.response;

import java.time.Instant;

/**
 * API Key 创建响应（{@code POST /api/console/api-keys}）。
 *
 * <p>历史实现返回 {@code Map.of("id", "keyName", "keyPrefix", "rawKey", "createdAt")}，键一字不差。 {@code
 * rawKey} 为明文密钥，仅此一次可见。由 controller 直接从 {@code ConsoleApiKeyService.CreateResult} 的强类型 来源构造。
 */
public record ConsoleApiKeyCreateResponse(
    Long id, String keyName, String keyPrefix, String rawKey, Instant createdAt) {}
