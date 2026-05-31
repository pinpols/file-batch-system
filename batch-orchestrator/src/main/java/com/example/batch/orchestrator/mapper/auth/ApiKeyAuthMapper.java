package com.example.batch.orchestrator.mapper.auth;

import com.example.batch.orchestrator.auth.ApiKeyRecord;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 只读访问 batch.api_key 表 — 给 {@link ApiKeyVerifier} 验 X-Batch-Api-Key header 用。
 *
 * <p>写路径(create/revoke/rotate)仍归 {@code ConsoleApiKeyService}(batch-console-api 模块),本 mapper 严格只读,
 * 不破"同表写路径单一入口"规则。
 */
@Mapper
public interface ApiKeyAuthMapper {

  /** 按 hash + tenant 精确查活跃 key — enabled=true 且 expires_at 未过期。 */
  Optional<ApiKeyRecord> findActiveByHashAndTenant(
      @Param("keyHash") String keyHash, @Param("tenantId") String tenantId);

  /** 异步更新 last_used_at(filter 不阻塞)。 */
  int touchLastUsedAt(@Param("id") Long id);
}
