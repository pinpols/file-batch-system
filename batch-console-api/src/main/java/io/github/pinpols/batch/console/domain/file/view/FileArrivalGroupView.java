package io.github.pinpols.batch.console.domain.file.view;

import java.time.Instant;
import lombok.Data;

@Data
public class FileArrivalGroupView {

  private String tenantId;
  private String fileGroupCode;
  private String waitFileGroupMode;
  private String requiredFileSet;
  private String arrivalTimeoutAction;
  private String arrivalState;
  private Instant expectedArrivalTime;
  private Instant latestTolerableTime;
  private Long arrivedCount;
  private Long triggeredCount;
  private Long timeoutCount;
  private Long waitingCount;
  private Instant lastUpdatedAt;
}
