package io.github.pinpols.batch.console.domain.rbac.service;

import io.github.pinpols.batch.console.domain.rbac.param.ConsoleApiKeyInsertParam;


import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.security.ApiKeyHasher;
import io.github.pinpols.batch.console.domain.rbac.entity.ApiKeyEntity;
import io.github.pinpols.batch.console.domain.rbac.mapper.ConsoleApiKeyMapper;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ConsoleApiKeyService {

  private static final SecureRandom SECURE_RANDOM = new SecureRandom();
  private static final int RAW_KEY_BYTES = 32;

  private final ConsoleApiKeyMapper repository;
  private final ConsoleTenantGuard tenantGuard;

  public List<ApiKeyEntity> list(String tenantId) {
    String resolvedTenantId = tenantGuard.resolveTenant(tenantId);
    return repository.findAllByTenant(resolvedTenantId);
  }

  public ApiKeyEntity detail(String tenantId, Long id) {
    String resolvedTenantId = tenantGuard.resolveTenant(tenantId);
    return repository
        .findByTenantAndId(resolvedTenantId, id)
        .orElseThrow(() -> BizException.of(ResultCode.NOT_FOUND, "error.api_key.not_found"));
  }

  /** 创建 API Key，返回明文密钥（仅此一次可见）。 */
  @Transactional
  public CreateResult create(
      String tenantId, String keyName, String scopes, Instant expiresAt, String operator) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    repository.findByTenantAndName(resolved, keyName).ifPresent(existing -> {
      throw BizException.of(ResultCode.CONFLICT, "error.api_key.name_exists");
    });

    String rawKey = generateRawKey();
    String prefix = rawKey.substring(0, 8);
    // P1-1(2026-06-03,docs/archive/audit/deep-scan-be-security-2026-06-03.md):
    // 新 key 一律 PBKDF2-HMAC-SHA256 + per-key 16B salt(裸 SHA-256 仅 verifier 兼容老行)。
    ApiKeyHasher.SaltedHash hashed = ApiKeyHasher.hashWithSaltKdf(rawKey);

    ConsoleApiKeyInsertParam consoleApiKeyInsertParam = ConsoleApiKeyInsertParam.builder()
        .tenantId(resolved)
        .keyName(keyName)
        .keyPrefix(prefix)
        .keyHash(hashed.hash())
        .salt(hashed.salt())
        .keyHashAlgo(ApiKeyHasher.ALGO_PBKDF2)
        .scopes(scopes == null || scopes.isBlank() ? "*" : scopes)
        .expiresAt(expiresAt)
        .operator(operator)
        .build();
    repository.insert(
        consoleApiKeyInsertParam);

    ApiKeyEntity entity = repository
        .findByTenantAndName(resolved, keyName)
        .orElseThrow(
            () -> BizException.of(ResultCode.SYSTEM_ERROR, "error.api_key.created_but_not_found"));
    return new CreateResult(entity, rawKey);
  }

  @Transactional
  public void revoke(String tenantId, Long id, String operator) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    repository
        .findByTenantAndId(resolved, id)
        .orElseThrow(() -> BizException.of(ResultCode.NOT_FOUND, "error.api_key.not_found"));
    repository.revoke(resolved, id, operator);
  }

  private String generateRawKey() {
    byte[] bytes = new byte[RAW_KEY_BYTES];
    SECURE_RANDOM.nextBytes(bytes);
    Base64.Encoder urlEncoder = Base64.getUrlEncoder().withoutPadding();
    String encodedKey = urlEncoder.encodeToString(bytes);
    return "bk_" + encodedKey;
  }

  public record CreateResult(ApiKeyEntity entity, String rawKey) {}
}
