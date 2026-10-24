package io.github.pinpols.batch.console.domain.workflow.mapper;

import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.console.domain.workflow.param.WorkflowDefinitionUpsertParam;
import io.github.pinpols.batch.console.domain.workflow.param.WorkflowDefinitionVersionUpdateParam;
import io.github.pinpols.batch.console.domain.workflow.param.WorkflowScheduleUpdateParam;
import io.github.pinpols.batch.console.domain.workflow.query.WorkflowDefinitionQuery;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface WorkflowDefinitionMapper {

  List<WorkflowDefinitionEntity> selectByQuery(WorkflowDefinitionQuery query);

  long countByQuery(WorkflowDefinitionQuery query);

  WorkflowDefinitionEntity selectByUniqueKey(String tenantId, String workflowCode, Integer version);

  WorkflowDefinitionEntity selectById(@Param("tenantId") String tenantId, @Param("id") Long id);

  /** 当 Workflow 日历变更会使周期性业务日预期脱离时返回 true。 */
  boolean hasCalendarScopedExpectations(@Param("tenantId") String tenantId, @Param("id") Long id);

  WorkflowDefinitionEntity selectBySingleJobDefinitionId(
      @Param("tenantId") String tenantId,
      @Param("singleJobDefinitionId") Long singleJobDefinitionId);

  int insert(WorkflowDefinitionEntity entity);

  int deleteByTenantAndId(@Param("tenantId") String tenantId, @Param("id") Long id);

  int toggleEnabled(
      @Param("tenantId") String tenantId, @Param("id") Long id, @Param("enabled") Boolean enabled);

  int toggleScheduleEnabled(
      @Param("tenantId") String tenantId,
      @Param("id") Long id,
      @Param("scheduleEnabled") Boolean scheduleEnabled);

  int updateScheduleConfiguration(WorkflowScheduleUpdateParam param);

  int upsertWorkflowDefinition(WorkflowDefinitionUpsertParam param);

  /** BE Spike(workflow-dag-designer): 全量替换路径专用,字段全更新 + version += 1。 返回受影响行数,0 表示乐观锁/租户不匹配未命中。 */
  int updateAndBumpVersion(WorkflowDefinitionVersionUpdateParam param);
}
