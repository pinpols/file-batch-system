package io.github.pinpols.batch.console.domain.job.mapper;

import io.github.pinpols.batch.console.domain.job.application.contract.request.JobDependencyRuleRequest;
import io.github.pinpols.batch.console.domain.job.entity.JobDefinitionEntity;
import io.github.pinpols.batch.console.domain.job.param.JobDefinitionMaintenanceUpdateParam;
import io.github.pinpols.batch.console.domain.job.param.JobMonitoringPolicyUpsertParam;
import io.github.pinpols.batch.console.domain.job.query.JobDefinitionQuery;
import io.github.pinpols.batch.console.domain.job.view.JobDefinitionCodeNameView;
import io.github.pinpols.batch.console.domain.job.view.JobDefinitionView;
import io.github.pinpols.batch.console.domain.job.view.JobDefinitionQueueRefView;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface JobDefinitionMapper {

  String setTenantContext(@Param("tenantId") String tenantId);

  List<JobDefinitionView> selectByQuery(JobDefinitionQuery query);

  long countByQuery(JobDefinitionQuery query);

  JobDefinitionView selectByUniqueKey(String tenantId, String jobCode);

  JobDefinitionView selectById(String tenantId, Long id);

  int insert(JobDefinitionEntity entity);

  int upsertJobMonitoringPolicy(JobMonitoringPolicyUpsertParam param);

  /**
   * 仅更新历史 legacy 依赖列，触发 DB 兼容同步；配置包两阶段应用用于把依赖写入推迟到全部 Job 落库之后。
   */
  int updateLegacyDependsOnJobCode(
      @Param("tenantId") String tenantId,
      @Param("jobCode") String jobCode,
      @Param("dependsOnJobCode") String dependsOnJobCode);

  int deleteDependencies(
      @Param("tenantId") String tenantId, @Param("downstreamJobCode") String downstreamJobCode);

  int insertDependencyRules(
      @Param("tenantId") String tenantId,
      @Param("downstreamJobCode") String downstreamJobCode,
      @Param("rules") List<JobDependencyRuleRequest> rules);

  List<JobDependencyEdge> selectAllDependencies(@Param("tenantId") String tenantId);

  /** 在当前写事务完成前，持有租户范围内的依赖图锁。 */
  int lockDependencyGraph(@Param("tenantId") String tenantId);

  record JobDependencyEdge(String downstreamJobCode, String upstreamJobCode) {}

  int copyJobMonitoringPolicy(
      @Param("tenantId") String tenantId,
      @Param("sourceJobDefinitionId") Long sourceJobDefinitionId,
      @Param("targetJobDefinitionId") Long targetJobDefinitionId,
      @Param("updatedBy") String updatedBy);

  int updateJobDefinitionMaintenance(JobDefinitionMaintenanceUpdateParam param);

  int deleteByTenantAndId(String tenantId, Long id);

  int toggleEnabled(String tenantId, Long id, Boolean enabled, String updatedBy);

  int batchToggleEnabled(
      @Param("tenantId") String tenantId,
      @Param("ids") List<Long> ids,
      @Param("enabled") Boolean enabled,
      @Param("updatedBy") String updatedBy);

  int copyJobDefinition(String tenantId, Long sourceId, String newJobCode, String createdBy);

  int clearPhysicalResourceAssignment(String tenantId, String jobCode, String updatedBy);

  /** BE Spike(workflow-dag-designer): 下拉数据源,仅 enabled=true,按 jobCode 升序返回 (jobCode, jobName)。 */
  List<JobDefinitionCodeNameView> selectActiveCodeNames(@Param("tenantId") String tenantId);

  /** 租户就绪自检专用:返回 enabled job 的 (jobCode, queueCode),供判定 queue_code 是否悬空。只读。 */
  List<JobDefinitionQueueRefView> selectEnabledJobQueueRefs(@Param("tenantId") String tenantId);
}
