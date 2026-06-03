package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.TenantQuotaPolicyEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * batch.tenant_quota_policy 只读 Mapper。CLAUDE.md §持久化"同一表禁双主入口":本表写入主入口在 {@code
 * batch-console-api},orch 端仅 SELECT + reconciler / snapshot 的 distinct tenant 枚举。
 */
public interface TenantQuotaPolicyMapper {

  List<TenantQuotaPolicyEntity> selectByTenantAndEnabled(
      @Param("tenantId") String tenantId, @Param("enabled") Boolean enabled);

  /** 取指定租户启用的第一条策略（按 id asc）。无对应记录返回 null。 */
  TenantQuotaPolicyEntity selectFirstEnabledByTenantId(
      @Param("tenantId") String tenantId, @Param("enabled") Boolean enabled);

  /** 列出 enabled=true 的所有 distinct tenantId；snapshot/reconciler 走全租户枚举。 */
  List<String> selectDistinctEnabledTenantIds();

  TenantQuotaPolicyEntity selectById(@Param("id") Long id);
}
