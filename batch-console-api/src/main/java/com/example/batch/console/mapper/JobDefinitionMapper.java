package com.example.batch.console.mapper;

import com.example.batch.console.domain.entity.JobDefinitionEntity;
import com.example.batch.console.domain.query.JobDefinitionQuery;
import java.util.List;

public interface JobDefinitionMapper {

    List<JobDefinitionEntity> selectByQuery(JobDefinitionQuery query);

    long countByQuery(JobDefinitionQuery query);

    JobDefinitionEntity selectByUniqueKey(String tenantId, String jobCode);

    int updateJobDefinitionMaintenance(String tenantId,
                                       String jobCode,
                                       String jobName,
                                       String queueCode,
                                       String workerGroup,
                                       String scheduleExpr,
                                       String calendarCode,
                                       String windowCode,
                                       String retryPolicy,
                                       Integer retryMaxCount,
                                       Integer timeoutSeconds,
                                       String shardStrategy,
                                       Boolean enabled,
                                       String description,
                                       String updatedBy);
}
