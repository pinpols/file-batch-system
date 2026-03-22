package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.TriggerRequestEntity;
import org.apache.ibatis.annotations.Param;

public interface TriggerRequestMapper {

    int insert(TriggerRequestEntity entity);

    TriggerRequestEntity selectByTenantAndRequestId(@Param("tenantId") String tenantId,
                                                    @Param("requestId") String requestId);

    int updateAcceptance(@Param("tenantId") String tenantId,
                         @Param("requestId") String requestId,
                         @Param("requestStatus") String requestStatus,
                         @Param("relatedJobInstanceId") Long relatedJobInstanceId);
}
