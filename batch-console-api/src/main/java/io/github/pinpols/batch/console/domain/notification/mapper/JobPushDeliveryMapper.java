package io.github.pinpols.batch.console.domain.notification.mapper;

import org.apache.ibatis.annotations.Param;
import io.github.pinpols.batch.console.domain.notification.entity.JobPushDelivery;
import java.time.Instant;
import java.util.List;

/** Job receipt and frozen device intentions share the caller's tenant-bound transaction. */
public interface JobPushDeliveryMapper {
  String bindTenant(@Param("tenantId") String tenantId);
  List<String> tenantPage(@Param("after") String after, @Param("limit") int limit);
  int reconcileStopped(@Param("tenantId") String tenantId, @Param("now") Instant now,
      @Param("maxAttempts") int maxAttempts, @Param("limit") int limit);
  List<JobPushDelivery> pending(@Param("tenantId") String tenantId,
      @Param("now") Instant now, @Param("limit") int limit);
  int claim(@Param("row") JobPushDelivery row, @Param("token") String token,
      @Param("now") Instant now, @Param("until") Instant until, @Param("maxAttempts") int maxAttempts);
  int finish(@Param("row") JobPushDelivery row, @Param("token") String token,
      @Param("status") String status, @Param("error") String error,
      @Param("httpStatus") Integer httpStatus, @Param("nextRetry") Instant nextRetry,
      @Param("refundAttempt") boolean refundAttempt);
  int insertDecision(@Param("tenantId") String tenantId, @Param("eventKey") String eventKey,
      @Param("jobId") long jobId, @Param("operator") String operator,
      @Param("decision") String decision, @Param("eventType") String eventType,
      @Param("payload") String payload);

  int insertDevice(@Param("tenantId") String tenantId, @Param("eventKey") String eventKey,
      @Param("jobId") long jobId, @Param("operator") String operator,
      @Param("subscriptionId") Long subscriptionId, @Param("fingerprint") String fingerprint,
      @Param("eventType") String eventType, @Param("payload") String payload);
}
