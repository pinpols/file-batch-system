package com.example.batch.console.mapper;

import com.example.batch.console.domain.entity.WorkflowDefinitionEntity;
import com.example.batch.console.domain.query.WorkflowDefinitionQuery;
import com.example.batch.console.mapper.param.WorkflowDefinitionUpsertParam;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface WorkflowDefinitionMapper {

    List<WorkflowDefinitionEntity> selectByQuery(WorkflowDefinitionQuery query);

    long countByQuery(WorkflowDefinitionQuery query);

    WorkflowDefinitionEntity selectByUniqueKey(String tenantId, String workflowCode, Integer version);

    WorkflowDefinitionEntity selectById(@Param("tenantId") String tenantId, @Param("id") Long id);

    int insert(WorkflowDefinitionEntity entity);

    int updateWorkflowDefinition(@Param("tenantId") String tenantId,
                                 @Param("id") Long id,
                                 @Param("workflowName") String workflowName,
                                 @Param("workflowType") String workflowType,
                                 @Param("enabled") Boolean enabled);

    int deleteByTenantAndId(@Param("tenantId") String tenantId, @Param("id") Long id);

    int toggleEnabled(@Param("tenantId") String tenantId, @Param("id") Long id, @Param("enabled") Boolean enabled);

    int upsertWorkflowDefinition(WorkflowDefinitionUpsertParam param);
}
