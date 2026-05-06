package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.WorkflowDefinitionEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * batch.workflow_definition CRUD。原 {@code WorkflowDefinitionRepository}（Spring Data JDBC）已下线，
 * 配置态写读统一由本 Mapper 接管。
 */
public interface WorkflowDefinitionMapper {

  /** 按 (tenantId, workflowCode, enabled) 取首条；用于 cache 服务回源。 */
  WorkflowDefinitionEntity selectFirstByTenantAndCodeAndEnabled(
      @Param("tenantId") String tenantId,
      @Param("workflowCode") String workflowCode,
      @Param("enabled") Boolean enabled);

  /** 列出指定 tenant 下指定启用状态的全部 workflow 定义；console 列表 / 缓存预热用。 */
  List<WorkflowDefinitionEntity> selectByTenantAndEnabled(
      @Param("tenantId") String tenantId, @Param("enabled") Boolean enabled);

  WorkflowDefinitionEntity selectById(@Param("id") Long id);

  /** ADR-025 reconciler：跨租户列出 enabled=true 全量。 */
  List<WorkflowDefinitionEntity> selectAllEnabled(@Param("limit") int limit);

  int insert(WorkflowDefinitionEntity record);

  int update(WorkflowDefinitionEntity record);

  int deleteById(@Param("id") Long id);
}
