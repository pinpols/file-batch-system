package com.example.batch.console.domain.rbac.service;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.security.ApiKeyHasher;
import com.example.batch.console.domain.rbac.entity.ApiKeyEntity;
import com.example.batch.console.domain.rbac.mapper.ConsoleApiKeyMapper;
import com.example.batch.console.domain.rbac.support.ConsoleTenantGuard;
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
    return repository.findAllByTenant(tenantGuard.resolveTenant(tenantId));
  }

  public ApiKeyEntity detail(String tenantId, Long id) {
    return repository
        .findByTenantAndId(tenantGuard.resolveTenant(tenantId), id)
        .orElseThrow(() -> BizException.of(ResultCode.NOT_FOUND, "error.api_key.not_found"));
  }

  /** 创建 API Key，返回明文密钥（仅此一次可见）。 */
  @Transactional
  public CreateResult create(
      String tenantId, String keyName, String scopes, Instant expiresAt, String operator) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    repository
        .findByTenantAndName(resolved, keyName)
        .ifPresent(
            existing -> {
              throw BizException.of(ResultCode.CONFLICT, "error.api_key.name_exists");
            });

    String rawKey = generateRawKey();
    String prefix = rawKey.substring(0, 8);
    // P1-1(2026-06-03,docs/analysis/2026-06-03-deep-scan-be-security.md):
    // 新 key 一律 PBKDF2-HMAC-SHA256 + per-key 16B salt(裸 SHA-256 仅 verifier 兼容老行)。
    ApiKeyHasher.SaltedHash hashed = ApiKeyHasher.hashWithSaltKdf(rawKey);

    repository.insert(
        resolved,
        keyName,
        prefix,
        hashed.hash(),
        hashed.salt(),
        ApiKeyHasher.ALGO_PBKDF2,
        scopes == null || scopes.isBlank() ? "*" : scopes,
        expiresAt,
        operator);

    ApiKeyEntity entity =
        repository
            .findByTenantAndName(resolved, keyName)
            .orElseThrow(
                () ->
                    BizException.of(
                        ResultCode.SYSTEM_ERROR, "error.api_key.created_but_not_found"));
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
    return "bk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public record CreateResult(ApiKeyEntity entity, String rawKey) {}
}
