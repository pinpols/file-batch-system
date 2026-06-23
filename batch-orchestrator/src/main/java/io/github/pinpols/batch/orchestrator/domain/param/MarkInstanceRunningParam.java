package io.github.pinpols.batch.orchestrator.domain.param;

import java.time.Instant;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class MarkInstanceRunningParam {
  private final String tenantId;
  private final Long id;
  private final String instanceStatus;
  private final Integer expectedPartitionCount;
  private final Instant startedAt;
  private final Long expectedVersion;
}
