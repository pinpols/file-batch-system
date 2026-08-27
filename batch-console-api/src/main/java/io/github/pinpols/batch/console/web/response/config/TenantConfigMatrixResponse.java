package io.github.pinpols.batch.console.web.response.config;

import java.util.List;

/** Same-job comparison matrix across tenants. */
public record TenantConfigMatrixResponse(
    String baselineTenantId,
    List<String> tenantIds,
    List<String> jobCodes,
    List<JobMatrixRow> rows) {

  public record JobMatrixRow(
      String tenantId,
      String jobCode,
      boolean exists,
      Boolean enabled,
      String scheduleType,
      String scheduleExpr,
      String timezone,
      String queueCode,
      String calendarCode,
      String windowCode,
      String workerGroup,
      List<String> pipelineJobCodes,
      List<String> workflowCodes,
      List<String> templateCodes,
      List<String> channelCodes,
      List<String> driftFields) {}
}
