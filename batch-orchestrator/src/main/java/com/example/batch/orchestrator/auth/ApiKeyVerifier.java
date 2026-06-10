package com.example.batch.orchestrator.auth;

import com.example.batch.common.security.ApiKeyHasher;
import com.example.batch.orchestrator.mapper.auth.ApiKeyAuthMapper;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 验证 X-Batch-Api-Key — 按 {@code key_prefix} 索引拿候选活跃行,逐行用 {@link ApiKeyHasher#verify} 常量时间比对。
 *
 * <p>匹配规则:hash + tenantId 必须双匹配(防租户冒充);记录 enabled + 未过期 + 未 revoke。
 *
 * <p>验证通过后异步 touch last_used_at(filter 不等),用于运维侧 "失效/僵尸 key 探测"。
 *
 * <p>scope 校验(ADR-035 §2):scope 字段是 comma/space-separated string,通配 {@code "*"} 命中任何检查; worker 操作类
 * endpoint 应调 {@link #verifyWithScope(String, String, String)} 要求显式 {@code "worker.execute"}
 * scope。老 key {@code scopes='*'}(V47 默认) 通配通过,无需轮转;新 key 必须显式带 {@code "worker.execute"} 才放行。
 *
 * <p>P1-1(2026-06-03,docs/analysis/2026-06-03-deep-scan-be-security.md): V166 起 api_key.key_hash 由裸
 * SHA-256 升级为 PBKDF2-HMAC-SHA256 + per-key salt。老 key {@code key_hash_algo='sha256'} 走原路径,验证
 * **命中**后异步 best-effort 升级为 PBKDF2,实现"登录即升级"。新 key 由 console-api 创建时即写 PBKDF2 + salt。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyVerifier {

  public static final String SCOPE_WORKER_EXECUTE = "worker.execute";
  public static final String SCOPE_WILDCARD = "*";

  /** 明文 key 前 8 位用作索引前缀(与 console-api ConsoleApiKeyService.create 同步)。 */
  public static final int KEY_PREFIX_LEN = 8;

  private final ApiKeyAuthMapper mapper;

  /**
   * 自注入(CLAUDE.md §Java #3 豁免①):{@code touchAsync} / {@code upgradeLegacyHashAsync} 标了
   * {@code @Async}, 必须经 Spring 代理调用才异步。原先 {@code this.touchAsync()} 是同类自调用,绕过代理 → @Async 失效 → DB 写
   * + PBKDF2(50-200ms CPU)在请求线程同步执行,洪峰打爆 Tomcat 线程池。改走 {@code self.xxx()}。
   */
  @Lazy @Autowired private ApiKeyVerifier self;

  /**
   * @param rawKey 客户端传来的 X-Batch-Api-Key 原文
   * @param claimedTenantId 客户端传来的 X-Batch-Tenant-Id;必须与 key 在表里的 tenant_id 一致
   * @return 命中且活跃的 {@link ApiKeyEntity};否则空(filter 直接返 401,不暴露具体原因防侧信道)
   */
  public Optional<ApiKeyEntity> verify(String rawKey, String claimedTenantId) {
    if (rawKey == null || rawKey.isBlank()) return Optional.empty();
    if (claimedTenantId == null || claimedTenantId.isBlank()) return Optional.empty();
    if (rawKey.length() < KEY_PREFIX_LEN) return Optional.empty();

    String prefix = rawKey.substring(0, KEY_PREFIX_LEN);
    List<ApiKeyEntity> candidates =
        mapper.findActiveCandidatesByPrefixAndTenant(prefix, claimedTenantId);
    if (candidates == null || candidates.isEmpty()) return Optional.empty();

    // 候选数极小(同一租户同一前缀的活跃 key,索引剪枝后通常 0-1 行,极端冲突 <5)。
    // 逐行常量时间比对,命中即停;legacy sha256 命中后异步升级 KDF。
    for (ApiKeyEntity rec : candidates) {
      String algo = rec.keyHashAlgo() == null ? ApiKeyHasher.ALGO_SHA256_LEGACY : rec.keyHashAlgo();
      if (ApiKeyHasher.verify(rawKey, rec.keyHash(), rec.salt(), algo)) {
        self.touchAsync(rec.id());
        if (ApiKeyHasher.ALGO_SHA256_LEGACY.equals(algo)) {
          self.upgradeLegacyHashAsync(rec.id(), rec.keyHash(), rawKey);
        }
        return Optional.of(rec);
      }
    }
    return Optional.empty();
  }

  /**
   * 同 {@link #verify} 但强制 key.scope 含 {@code requiredScope}(或 {@code "*"} 通配)。
   *
   * @return 命中且 scope 通过的 {@link ApiKeyEntity};否则空(scope 不通过也返空,filter 一律 401,不区分原因)
   */
  public Optional<ApiKeyEntity> verifyWithScope(
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
  public void touchAsync(Long id) {
    try {
      mapper.touchLastUsedAt(id);
    } catch (Exception ex) {
      log.debug("touch last_used_at failed for keyId={}: {}", id, ex.getMessage());
    }
  }

  /**
   * P1-1:legacy sha256 行命中后,best-effort 升级为 PBKDF2 + salt。
   *
   * <p>WHERE 守护 {@code algo='sha256' AND key_hash=oldHash} 防并发改写覆盖 — 同时被 console-api revoke 或被另一
   * worker 并发升级的场景下,落败方无副作用退出。
   */
  @Async
  public void upgradeLegacyHashAsync(Long id, String oldHash, String rawKey) {
    try {
      ApiKeyHasher.SaltedHash upgraded = ApiKeyHasher.hashWithSaltKdf(rawKey);
      int rows = mapper.upgradeHashIfLegacy(id, oldHash, upgraded.hash(), upgraded.salt());
      if (rows > 0) {
        log.info("api_key keyId={} upgraded sha256 → pbkdf2", id);
      }
    } catch (Exception ex) {
      log.debug("api_key keyId={} kdf upgrade swallowed: {}", id, ex.getMessage());
    }
  }
}
