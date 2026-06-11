package com.example.batch.orchestrator.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/**
 * 租户路由查询 mapper — 为 {@link
 * com.example.batch.orchestrator.infrastructure.tenant.ActiveTenantProvider} 提供 batch.tenant
 * 活跃租户清单的数据访问入口。
 *
 * <p>写路径(创建/停用/激活租户)归 batch-console-api 的 ConsoleTenantApplicationService,本 mapper 严格只读。
 */
@Mapper
public interface TenantRoutingMapper {

  /**
   * 查询所有状态为 ACTIVE 的租户标识列表,按 tenant_id 升序排列。
   *
   * @return 活跃租户 tenant_id 列表
   */
  List<String> selectActiveTenantIds();
}
