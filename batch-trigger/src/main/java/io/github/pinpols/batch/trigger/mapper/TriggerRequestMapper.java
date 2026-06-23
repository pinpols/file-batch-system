package io.github.pinpols.batch.trigger.mapper;

import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.trigger.domain.query.TriggerRequestQuery;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface TriggerRequestMapper {

  List<TriggerRequestEntity> selectByQuery(TriggerRequestQuery query);

  TriggerRequestEntity selectById(@Param("id") Long id);

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

  int updateRelatedJobInstanceId(
      @Param("tenantId") String tenantId,
      @Param("requestId") String requestId,
      @Param("relatedJobInstanceId") Long relatedJobInstanceId);
}
