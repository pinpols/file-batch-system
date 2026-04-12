package com.example.batch.orchestrator.domain.query;

import lombok.Data;

@Data
public class BatchDayInstanceMetrics {

  private Long totalCount;
  private Long activeCount;
  private Long successCount;
  private Long failedCount;
}
