package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.TenantQuotaPolicyEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * batch.tenant_quota_policy CRUD。原 {@code TenantQuotaPolicyRepository}（Spring Data JDBC）已下线， 配额策略读取
 * + reconciler / snapshot 的 distinct tenant 枚举统一由本 Mapper 接管。
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

  int insert(TenantQuotaPolicyEntity record);

  int update(TenantQuotaPolicyEntity record);

  int deleteById(@Param("id") Long id);
}
