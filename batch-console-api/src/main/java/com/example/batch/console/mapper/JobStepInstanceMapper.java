package com.example.batch.console.mapper;

import com.example.batch.console.domain.entity.JobStepInstanceEntity;
import com.example.batch.console.domain.query.JobStepInstanceQuery;

import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface JobStepInstanceMapper {

    List<JobStepInstanceEntity> selectByQuery(JobStepInstanceQuery query);

    JobStepInstanceEntity selectById(@Param("tenantId") String tenantId, @Param("id") Long id);

    long countByQuery(JobStepInstanceQuery query);
}
