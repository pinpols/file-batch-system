package io.github.pinpols.batch.console.domain.workflow.mapper;

import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowDefinitionVersionEntity;
import io.github.pinpols.batch.console.domain.workflow.param.WorkflowDefinitionVersionInsertParam;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * workflow-dag-designer Polish — {@code batch.workflow_definition_version}(V167)读写 mapper。
 *
 * <p>多租 UNIQUE(tenant_id, workflow_definition_id, version)由 DB 回退;mapper WHERE 一律强制带 tenant_id, 见
 * MapperXmlTenantGuardArchTest 守护。
 */
public interface WorkflowDefinitionVersionMapper {

  /** INSERT 一条快照,useGeneratedKeys 回写 id 到 param。 */
  int insertVersionSnapshot(@Param("p") WorkflowDefinitionVersionInsertParam param);

  /** 按 (tenant_id, workflow_definition_id) 拉所有历史版本,version desc 排序。 */
  List<WorkflowDefinitionVersionEntity> listByDefinitionId(
      @Param("tenantId") String tenantId, @Param("workflowDefinitionId") Long workflowDefinitionId);

  /** 按 (tenant_id, workflow_definition_id, version) 取单版本快照;不存在返回 null。 */
  WorkflowDefinitionVersionEntity findByDefinitionIdAndVersion(
      @Param("tenantId") String tenantId,
      @Param("workflowDefinitionId") Long workflowDefinitionId,
      @Param("version") Integer version);
}
