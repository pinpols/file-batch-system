package com.example.batch.orchestrator.auth;

import com.example.batch.orchestrator.mapper.auth.ApiKeyAuthMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 验证 X-Batch-Api-Key — SHA-256 hash 后查 batch.api_key 表。
 *
 * <p>匹配规则:hash + tenantId 必须双匹配(防租户冒充);记录 enabled + 未过期 + 未 revoke。
 *
 * <p>验证通过后异步 touch last_used_at(filter 不等),用于运维侧 "失效/僵尸 key 探测"。
 *
 * <p>scope 校验(ADR-035 §2):scope 字段是 comma/space-separated string,通配 {@code "*"} 命中任何检查; worker 操作类
 * endpoint 应调 {@link #verifyWithScope(String, String, String)} 要求显式 {@code "worker.execute"}
 * scope。老 key {@code scopes='*'}(V47 默认) 通配通过,无需轮转;新 key 必须显式带 {@code "worker.execute"} 才放行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyVerifier {

  public static final String SCOPE_WORKER_EXECUTE = "worker.execute";
  public static final String SCOPE_WILDCARD = "*";

  private final ApiKeyAuthMapper mapper;

  /**
   * @param rawKey 客户端传来的 X-Batch-Api-Key 原文
   * @param claimedTenantId 客户端传来的 X-Batch-Tenant-Id;必须与 key 在表里的 tenant_id 一致
   * @return 命中且活跃的 {@link ApiKeyRecord};否则空(filter 直接返 401,不暴露具体原因防侧信道)
   */
  public Optional<ApiKeyRecord> verify(String rawKey, String claimedTenantId) {
    if (rawKey == null || rawKey.isBlank()) return Optional.empty();
    if (claimedTenantId == null || claimedTenantId.isBlank()) return Optional.empty();
    String hash = sha256Hex(rawKey);
    Optional<ApiKeyRecord> hit = mapper.findActiveByHashAndTenant(hash, claimedTenantId);
    hit.ifPresent(rec -> touchAsync(rec.id()));
    return hit;
  }

  /**
   * 同 {@link #verify} 但强制 key.scope 含 {@code requiredScope}(或 {@code "*"} 通配)。
   *
   * @return 命中且 scope 通过的 {@link ApiKeyRecord};否则空(scope 不通过也返空,filter 一律 401,不区分原因)
   */
  public Optional<ApiKeyRecord> verifyWithScope(
      String rawKey, String claimedTenantId, String requiredScope) {
    return verify(rawKey, claimedTenantId).filter(rec -> scopesAllow(rec.scopes(), requiredScope));
  }

  /** scopes 字符串解析:逗号/空格 split,trim,去空;{@code "*"} 通配。 */
  static boolean scopesAllow(String scopesField, String requiredScope) {
    if (requiredScope == null || requiredScope.isBlank()) return true;
    if (scopesField == null || scopesField.isBlank()) return false;
    Set<String> scopes =
        Arrays.stream(scopesField.split("[,\\s]+"))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toSet());
    return scopes.contains(SCOPE_WILDCARD) || scopes.contains(requiredScope);
  }

  @Async
  void touchAsync(Long id) {
    try {
      mapper.touchLastUsedAt(id);
    } catch (Exception ex) {
      log.debug("touch last_used_at failed for keyId={}: {}", id, ex.getMessage());
    }
  }

  private static String sha256Hex(String input) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
