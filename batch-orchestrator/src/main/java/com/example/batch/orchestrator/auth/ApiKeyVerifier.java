package com.example.batch.orchestrator.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyVerifier {

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
