package io.github.pinpols.batch.console.domain.notification.mapper;

import io.github.pinpols.batch.console.domain.notification.view.TerminalNotificationArchiveCandidate;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 终态通知专用归档；不处理非终态订阅投递或其他业务审计。 */
public interface TerminalNotificationArchiveMapper {
  enum Family { RECEIPT, WEBHOOK, RULE, PUSH, AUDIT }

  List<String> enabledTenants(@Param("after") String after, @Param("limit") int limit);

  List<TerminalNotificationArchiveCandidate> candidates(
      @Param("tenantId") String tenantId, @Param("limit") int limit);

  Boolean lockReadyReceipt(@Param("candidate") TerminalNotificationArchiveCandidate candidate);

  int copyFamily(
      @Param("candidate") TerminalNotificationArchiveCandidate candidate,
      @Param("family") Family family);

  long mismatches(
      @Param("candidate") TerminalNotificationArchiveCandidate candidate,
      @Param("family") Family family);

  int deleteFamily(
      @Param("candidate") TerminalNotificationArchiveCandidate candidate,
      @Param("family") Family family);
}
