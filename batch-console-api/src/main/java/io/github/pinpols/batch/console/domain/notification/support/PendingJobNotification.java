package io.github.pinpols.batch.console.domain.notification.support;

import java.time.Instant;
import lombok.Data;

/** {@link ConsolePushJobNotifier} 扫描结果 DTO,仅在推送链路内部传递。 */
@Data
public class PendingJobNotification {
  private Long jobInstanceId;
  private String tenantId;
  private String operatorId;
  private String jobCode;
  private String instanceStatus;
  private Instant finishedAt;
}
