package com.example.batch.console.domain.job.mapper;

import com.example.batch.console.domain.job.entity.JobDefinitionEntity;
import com.example.batch.console.domain.job.param.JobDefinitionMaintenanceUpdateParam;
import com.example.batch.console.domain.job.query.JobDefinitionQuery;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface JobDefinitionMapper {

  List<JobDefinitionEntity> selectByQuery(JobDefinitionQuery query);

  long countByQuery(JobDefinitionQuery query);

  JobDefinitionEntity selectByUniqueKey(String tenantId, String jobCode);

  JobDefinitionEntity selectById(String tenantId, Long id);

  int insert(JobDefinitionEntity entity);

  int updateJobDefinitionMaintenance(JobDefinitionMaintenanceUpdateParam param);

  int deleteByTenantAndId(String tenantId, Long id);

  int toggleEnabled(String tenantId, Long id, Boolean enabled, String updatedBy);

  int batchToggleEnabled(
      @Param("tenantId") String tenantId,
      @Param("ids") List<Long> ids,
      @Param("enabled") Boolean enabled,
      @Param("updatedBy") String updatedBy);

  int copyJobDefinition(String tenantId, Long sourceId, String newJobCode, String createdBy);

  /** BE Spike(workflow-dag-designer): 下拉数据源,仅 enabled=true,按 jobCode 升序返回 (jobCode, jobName)。 */
  List<com.example.batch.console.domain.workflow.web.response.CodeNameOption> selectActiveCodeNames(
      @Param("tenantId") String tenantId);

  /** 租户就绪自检专用:返回 enabled job 的 (jobCode, queueCode),供判定 queue_code 是否悬空。只读。 */
  List<Map<String, Object>> selectEnabledJobQueueRefs(@Param("tenantId") String tenantId);
}
