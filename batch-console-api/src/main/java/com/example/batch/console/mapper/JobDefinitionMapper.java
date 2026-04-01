package com.example.batch.console.mapper;

import com.example.batch.console.domain.entity.JobDefinitionEntity;
import com.example.batch.console.domain.query.JobDefinitionQuery;
import com.example.batch.console.mapper.param.JobDefinitionMaintenanceUpdateParam;
import java.util.List;

public interface JobDefinitionMapper {

    List<JobDefinitionEntity> selectByQuery(JobDefinitionQuery query);

    long countByQuery(JobDefinitionQuery query);

    JobDefinitionEntity selectByUniqueKey(String tenantId, String jobCode);

    JobDefinitionEntity selectById(String tenantId, Long id);

    int insert(JobDefinitionEntity entity);

    int updateJobDefinitionMaintenance(JobDefinitionMaintenanceUpdateParam param);

    int deleteByTenantAndId(String tenantId, Long id);

    int toggleEnabled(String tenantId, Long id, Boolean enabled, String updatedBy);

    int copyJobDefinition(String tenantId, Long sourceId, String newJobCode, String createdBy);
}
