package io.github.pinpols.batch.console.domain.notification.mapper;

import io.github.pinpols.batch.console.domain.notification.application.contract.response.TerminalDeliveryRecoveryResponse;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface TerminalNotificationRecoveryMapper {
  String bindTenant(@Param("tenantId") String tenantId);

  List<TerminalDeliveryRecoveryResponse> list(
      @Param("tenantId") String tenantId, @Param("limit") int limit);

  TerminalDeliveryRecoveryResponse lock(
      @Param("tenantId") String tenantId, @Param("source") String source, @Param("id") Long id);

  TerminalDeliveryRecoveryResponse archived(
      @Param("tenantId") String tenantId, @Param("source") String source, @Param("id") Long id);

  String replayAudit(
      @Param("tenantId") String tenantId,
      @Param("aggregateId") String aggregateId,
      @Param("requestId") String requestId);

  default int rearm(String tenantId, String source, Long id) {
    final String pendingStatus = "PENDING";
    return rearmWithStatus(tenantId, source, id, pendingStatus);
  }

  int rearmWithStatus(
      @Param("tenantId") String tenantId,
      @Param("source") String source,
      @Param("id") Long id,
      @Param("targetStatus") String targetStatus);
}
