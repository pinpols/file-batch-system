package io.github.pinpols.batch.orchestrator.domain.entity;

import lombok.Data;

@Data
public class BatchDayInstanceMetrics {

  private Long totalCount;
  private Long activeCount;
  private Long successCount;
  private Long failedCount;
}
