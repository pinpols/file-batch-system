package io.github.pinpols.batch.console.domain.ops.mapper;

import io.github.pinpols.batch.console.domain.ops.entity.CustomTaskTypeEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** console-api 只读访问 {@code custom_task_type_registry}（SDK Phase 3 M3.1 / API-P3-1）。 */
public interface CustomTaskTypeMapper {

  /** 按租户列 ACTIVE 自定义 taskType，last_declared_at 倒序（最近上报在前）。 */
  List<CustomTaskTypeEntity> selectActiveByTenant(@Param("tenantId") String tenantId);

  long countActiveByTenant(@Param("tenantId") String tenantId);

  CustomTaskTypeEntity selectByTenantAndCode(
      @Param("tenantId") String tenantId, @Param("taskTypeCode") String taskTypeCode);
}
