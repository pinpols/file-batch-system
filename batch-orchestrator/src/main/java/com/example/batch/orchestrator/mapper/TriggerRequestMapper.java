package com.example.batch.orchestrator.mapper;

import com.example.batch.common.persistence.entity.TriggerRequestEntity;
import org.apache.ibatis.annotations.Param;

public interface TriggerRequestMapper {

  int insert(TriggerRequestEntity entity);

  TriggerRequestEntity selectByTenantAndRequestId(
      @Param("tenantId") String tenantId, @Param("requestId") String requestId);

  TriggerRequestEntity selectByTenantAndDedupKey(
      @Param("tenantId") String tenantId, @Param("dedupKey") String dedupKey);

  int updateAcceptance(
      @Param("tenantId") String tenantId,
      @Param("requestId") String requestId,
      @Param("requestStatus") String requestStatus,
      @Param("relatedJobInstanceId") Long relatedJobInstanceId);

  int updateTriggerType(
      @Param("tenantId") String tenantId,
      @Param("requestId") String requestId,
      @Param("triggerType") String triggerType);
}
