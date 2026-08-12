package io.github.pinpols.batch.orchestrator.auth;

import io.github.pinpols.batch.common.security.ApiKeyHasher;
import io.github.pinpols.batch.orchestrator.mapper.auth.ApiKeyAuthMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * API Key 验证后的异步维护动作。
 *
 * <p>把低价值、可降级的 last-used 写入和旧哈希升级隔离出验证主链路，确保 {@link Async} 代理一定落在跨 Bean
 * 调用边界上；维护失败只影响运维元数据，不得拒绝已经通过的认证请求。
 */
@Slf4j
@Component
@RequiredArgsConstructor
class ApiKeyAsyncMaintenance {

  private final ApiKeyAuthMapper mapper;

  @Async
  void touch(Long id) {
    try {
      mapper.touchLastUsedAt(id);
    } catch (Exception ex) {
      log.debug("touch last_used_at failed for keyId={}: {}", id, ex.getMessage());
    }
  }

  /** 使用 CAS 条件升级旧哈希；并发轮转、吊销或其他节点先完成升级时，无副作用退出。 */
  @Async
  void upgradeLegacyHash(Long id, String oldHash, String rawKey) {
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
