package io.github.pinpols.batch.orchestrator.mapper.auth;

import io.github.pinpols.batch.orchestrator.auth.ApiKeyEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 只读 + 哈希升级访问 batch.api_key 表 — 给 {@link ApiKeyVerifier} 验 X-Batch-Api-Key header 用。
 *
 * <p>写路径(create/revoke/rotate)仍归 {@code ConsoleApiKeyService}(batch-console-api 模块),本 mapper 严格只读 +
 * 仅 hash 升级写(P1-1 V166 起 sha256 → pbkdf2 lazy 升级,不破"同表写路径单一入口"原则,运维侧透明)。
 */
@Mapper
public interface ApiKeyAuthMapper {

  /**
   * P1-1(V166):按 {@code key_prefix}(明文前 8 位)+ tenant 取候选活跃行;由调用方按 KDF / SHA256 algo 与 {@code
   * key_hash} / {@code salt} 常量时间比对。
   */
  List<ApiKeyEntity> findActiveCandidatesByPrefixAndTenant(
      @Param("keyPrefix") String keyPrefix, @Param("tenantId") String tenantId);

  /** 异步更新 last_used_at(filter 不阻塞)。 */
  int touchLastUsedAt(@Param("id") Long id);

  /**
   * P1-1(V166):legacy {@code sha256} 行命中验证后,best-effort 升级为 PBKDF2 + salt。
   *
   * <p>WHERE 子句严格匹配 {@code algo='sha256' AND key_hash=oldHash} —— 防并发改写覆盖 console-api 同时 revoke /
   * 轮转 的 key,二者择一胜出。
   */
  int upgradeHashIfLegacy(
      @Param("id") Long id,
      @Param("oldHash") String oldHash,
      @Param("newHash") String newHash,
      @Param("newSalt") String newSalt);
}
