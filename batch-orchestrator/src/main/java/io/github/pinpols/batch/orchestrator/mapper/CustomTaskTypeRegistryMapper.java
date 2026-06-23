package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.entity.CustomTaskTypeRegistryEntity;
import io.github.pinpols.batch.orchestrator.domain.param.CustomTaskTypeUpsertParam;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * {@code batch.custom_task_type_registry} 访问(SDK Phase 3 M3.1,V159)。
 *
 * <p>{@link #upsertDeclared} 由 worker register 触发(source=SDK_DECLARED);读路径供派单合并 / console 表单。
 */
public interface CustomTaskTypeRegistryMapper {

  /** SDK 上报 upsert — 冲突键 (tenant_id, task_type_code),命中刷新 descriptor / 版本 / 上报 worker。 */
  int upsertDeclared(CustomTaskTypeUpsertParam param);

  CustomTaskTypeRegistryEntity selectByTenantAndCode(
      @Param("tenantId") String tenantId, @Param("taskTypeCode") String taskTypeCode);

  List<CustomTaskTypeRegistryEntity> selectActiveByTenant(@Param("tenantId") String tenantId);
}
