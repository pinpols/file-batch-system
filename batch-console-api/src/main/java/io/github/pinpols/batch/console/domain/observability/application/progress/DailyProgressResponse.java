package io.github.pinpols.batch.console.domain.observability.application.progress;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record DailyProgressResponse(
    String tenantId,
    LocalDate bizDate,
    Instant generatedAt,
    List<StatusCount> counts,
    Coverage coverage) {
  public record StatusCount(String scope, String status, Long count) {}

  public record Coverage(Long scheduledDefinitions, Long uncoveredDefinitions) {}
}
