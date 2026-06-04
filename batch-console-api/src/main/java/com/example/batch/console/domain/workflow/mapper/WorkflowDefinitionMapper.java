package com.example.batch.console.domain.workflow.mapper;

import com.example.batch.console.domain.workflow.entity.WorkflowDefinitionEntity;
import com.example.batch.console.domain.workflow.param.WorkflowDefinitionUpsertParam;
import com.example.batch.console.domain.workflow.query.WorkflowDefinitionQuery;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface WorkflowDefinitionMapper {

  List<WorkflowDefinitionEntity> selectByQuery(WorkflowDefinitionQuery query);

  long countByQuery(WorkflowDefinitionQuery query);

  WorkflowDefinitionEntity selectByUniqueKey(String tenantId, String workflowCode, Integer version);

  WorkflowDefinitionEntity selectById(@Param("tenantId") String tenantId, @Param("id") Long id);

  int insert(WorkflowDefinitionEntity entity);

  int updateWorkflowDefinition(
      @Param("tenantId") String tenantId,
      @Param("id") Long id,
      @Param("workflowName") String workflowName,
      @Param("workflowType") String workflowType,
      @Param("enabled") Boolean enabled);

  int deleteByTenantAndId(@Param("tenantId") String tenantId, @Param("id") Long id);

  int toggleEnabled(
      @Param("tenantId") String tenantId, @Param("id") Long id, @Param("enabled") Boolean enabled);

  int upsertWorkflowDefinition(WorkflowDefinitionUpsertParam param);

  /** BE Spike(workflow-dag-designer): 全量替换路径专用,字段全更新 + version += 1。 返回受影响行数,0 表示乐观锁/租户不匹配未命中。 */
  int updateAndBumpVersion(
      @Param("tenantId") String tenantId,
      @Param("id") Long id,
      @Param("expectedVersion") Integer expectedVersion,
      @Param("workflowName") String workflowName,
      @Param("workflowType") String workflowType,
      @Param("enabled") Boolean enabled);
}
