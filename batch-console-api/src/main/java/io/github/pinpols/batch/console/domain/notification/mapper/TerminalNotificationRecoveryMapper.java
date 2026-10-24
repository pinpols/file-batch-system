package io.github.pinpols.batch.console.domain.notification.mapper;

import io.github.pinpols.batch.console.domain.notification.application.contract.response.TerminalDeliveryRecoveryResponse;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface TerminalNotificationRecoveryMapper {
  List<TerminalDeliveryRecoveryResponse> list(@Param("tenantId") String tenantId, @Param("limit") int limit);
  TerminalDeliveryRecoveryResponse lock(@Param("tenantId") String tenantId, @Param("source") String source, @Param("id") Long id);
  String replayAudit(@Param("tenantId") String tenantId, @Param("aggregateId") String aggregateId,
      @Param("requestId") String requestId);
  int rearm(@Param("tenantId") String tenantId, @Param("source") String source, @Param("id") Long id);
}
