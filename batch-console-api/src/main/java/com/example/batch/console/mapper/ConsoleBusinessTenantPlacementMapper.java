package com.example.batch.console.mapper;

import com.example.batch.common.persistence.entity.BusinessTenantPlacementEntity;
import com.example.batch.console.domain.param.BusinessTenantPlacementUpsertParam;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * {@code batch.business_tenant_placement} 管理 mapper(console 写路径)。
 *
 * <p>平台 ROLE_ADMIN 跨租维护:全表列出 / 指派(迁片)/ 取消(回退 hash)。复用 batch-common 的行类型 {@link
 * BusinessTenantPlacementEntity}(worker 侧只读,console 侧读写,两路 mapper 各自独立,不双主入口)。
 */
public interface ConsoleBusinessTenantPlacementMapper {

  /** 全表列出(平台 admin 跨租视图,无 tenant 过滤——本表即「哪个租户在哪片」的全局映射)。 */
  List<BusinessTenantPlacementEntity> findAll();

  /** ON CONFLICT (tenant_id) DO UPDATE 语义:指派或迁片。 */
  void upsert(@Param("p") BusinessTenantPlacementUpsertParam p);

  /** 取消指派(该租户回退 hash 默认),返回删除行数。 */
  int deleteByTenant(@Param("tenantId") String tenantId);
}
