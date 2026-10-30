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

  /** 按精确编码读取当前最高版本，供配置写入和创建去重定位稳定身份。 */
  WorkflowDefinitionEntity selectLatestByCode(
      @Param("tenantId") String tenantId, @Param("workflowCode") String workflowCode);

  /** 配置包按编码只读取当前版本，不将旧定义重复带入目标应用集合。 */
  List<WorkflowDefinitionEntity> selectLatestByTenant(@Param("tenantId") String tenantId);

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
