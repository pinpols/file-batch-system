package com.example.batch.trigger.mapper;

import com.example.batch.common.persistence.entity.TriggerRequestEntity;
import com.example.batch.trigger.domain.query.TriggerRequestQuery;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface TriggerRequestMapper {

  List<TriggerRequestEntity> selectByQuery(TriggerRequestQuery query);

  TriggerRequestEntity selectByTenantAndRequestId(
      @Param("tenantId") String tenantId, @Param("requestId") String requestId);

  TriggerRequestEntity selectByTenantAndDedupKey(
      @Param("tenantId") String tenantId, @Param("dedupKey") String dedupKey);

  int insert(TriggerRequestEntity entity);

  int updateRequestStatus(
      @Param("tenantId") String tenantId,
      @Param("requestId") String requestId,
      @Param("requestStatus") String requestStatus);

  /**
   * CAS-style conditional update: only changes status when current status matches {@code
   * expectedStatus}.
   */
  int updateRequestStatusConditional(
      @Param("tenantId") String tenantId,
      @Param("requestId") String requestId,
      @Param("requestStatus") String requestStatus,
      @Param("expectedStatus") String expectedStatus);

  List<TriggerRequestEntity> selectForwardFailedForRetry(
      @Param("maxRetries") int maxRetries,
      @Param("createdAfter") Instant createdAfter,
      @Param("limit") int limit);

  int incrementForwardRetryCount(
      @Param("tenantId") String tenantId,
      @Param("requestId") String requestId,
      @Param("requestStatus") String requestStatus);

  int updateRelatedJobInstanceId(
      @Param("tenantId") String tenantId,
      @Param("requestId") String requestId,
      @Param("relatedJobInstanceId") Long relatedJobInstanceId);
}
