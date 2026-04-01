package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.CompensationCommandEntity;
import org.apache.ibatis.annotations.Param;

public interface CompensationCommandMapper {

    int insert(CompensationCommandEntity entity);

    CompensationCommandEntity selectById(@Param("tenantId") String tenantId,
                                         @Param("id") Long id);

    int updateStatus(UpdateCompensationStatusParam param);

    int countRunningByTarget(@Param("tenantId") String tenantId,
                             @Param("compensationType") String compensationType,
                             @Param("targetId") Long targetId,
                             @Param("runningStatus") String runningStatus);
}
