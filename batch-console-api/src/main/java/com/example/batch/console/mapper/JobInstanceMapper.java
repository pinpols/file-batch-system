package com.example.batch.console.mapper;

import com.example.batch.console.domain.entity.JobInstanceEntity;
import com.example.batch.console.domain.query.JobInstanceQuery;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface JobInstanceMapper {

    List<JobInstanceEntity> selectByQuery(JobInstanceQuery query);

    JobInstanceEntity selectById(@Param("tenantId") String tenantId, @Param("id") Long id);

    long countByQuery(JobInstanceQuery query);

    long countByStatuses(@Param("tenantId") String tenantId, @Param("statuses") List<String> statuses);

    long countSlaBreaches(@Param("tenantId") String tenantId, @Param("activeStatuses") List<String> activeStatuses);

    List<JobInstanceEntity> selectByInstanceNos(@Param("tenantId") String tenantId,
                                                @Param("instanceNos") List<String> instanceNos);
}
