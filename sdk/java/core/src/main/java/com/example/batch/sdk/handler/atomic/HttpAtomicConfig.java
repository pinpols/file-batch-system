package com.example.batch.sdk.handler.atomic;

import java.util.Objects;
import java.util.Set;

/**
 * {@link HttpAtomicHandler} 的配置。开箱即用的 HTTP 原子执行器调用约束(SSRF 防护 / method 白名单 / 超时 / 响应大小上限)。
 *
 * @param taskType 注册的 taskType(默认 "http")
 * @param blockPrivateIps 是否拦截私网 / 环回 / 链路本地 / 站点本地地址(SSRF 防护,默认 true)
 * @param blockedHostPatterns 额外的 host 黑名单(简单 contains 或正则匹配),默认空
 * @param allowedMethods 允许的 HTTP method(大写),默认 GET/POST/PUT/DELETE/PATCH/HEAD
 * @param timeoutSeconds connect + request 超时秒数(默认 30)
 * @param maxResponseBytes 响应体最大字节数,超出截断(默认 1MB)
 */
public record HttpAtomicConfig(
    String taskType,
    boolean blockPrivateIps,
    Set<String> blockedHostPatterns,
    Set<String> allowedMethods,
    int timeoutSeconds,
    int maxResponseBytes) {

  private static final Set<String> DEFAULT_METHODS =
      Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD");

  public HttpAtomicConfig {
    Objects.requireNonNull(taskType, "taskType");
    blockedHostPatterns = blockedHostPatterns == null ? Set.of() : Set.copyOf(blockedHostPatterns);
    allowedMethods =
        allowedMethods == null || allowedMethods.isEmpty()
            ? DEFAULT_METHODS
            : Set.copyOf(allowedMethods);
    if (timeoutSeconds <= 0) {
      throw new IllegalArgumentException("timeoutSeconds must be > 0");
    }
    if (maxResponseBytes <= 0) {
      throw new IllegalArgumentException("maxResponseBytes must be > 0");
    }
  }

  /** 以指定 taskType 构造默认配置(blockPrivateIps=true,默认 method 全集,30s,1MB)。 */
  public static HttpAtomicConfig defaults(String taskType) {
    return new HttpAtomicConfig(taskType, true, Set.of(), DEFAULT_METHODS, 30, 1024 * 1024);
  }
}
