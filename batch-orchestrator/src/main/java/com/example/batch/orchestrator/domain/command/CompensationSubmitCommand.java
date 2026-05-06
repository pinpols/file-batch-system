package com.example.batch.orchestrator.domain.command;

import java.time.LocalDate;
import lombok.Builder;

@Builder
public record CompensationSubmitCommand(
    String tenantId,
    String compensationType,
    Long targetId,
    String targetInstanceNo,
    /** 补偿范围对应的业务 Job 标识。 */
    String jobCode,
    LocalDate bizDate,
    String batchNo,
    Long relatedFileId,
    String channelCode,
    String reason,
    String operatorId,
    String approvalId,
    String strategy,
    String traceId,
    /** §5.5 — 补跑结果版本策略（CREATE_NEW_VERSION / KEEP_BOTH / MANUAL_CONFIRM_EFFECTIVE）。 */
    String resultPolicy,
    /** §5.5 — 补跑配置版本策略（USE_ORIGINAL_CONFIG / USE_LATEST_CONFIG / USE_SPECIFIED_VERSION）。 */
    String configVersionPolicy,
    /** USE_SPECIFIED_VERSION 时使用的具体 job_definition_version。 */
    Integer configVersion) {

  /** 兼容旧测试与历史调用的 14-arg 构造，rerun policy 字段补 null。 */
  public CompensationSubmitCommand(
      String tenantId,
      String compensationType,
      Long targetId,
      String targetInstanceNo,
      String jobCode,
      LocalDate bizDate,
      String batchNo,
      Long relatedFileId,
      String channelCode,
      String reason,
      String operatorId,
      String approvalId,
      String strategy,
      String traceId) {
    this(
        tenantId,
        compensationType,
        targetId,
        targetInstanceNo,
        jobCode,
        bizDate,
        batchNo,
        relatedFileId,
        channelCode,
        reason,
        operatorId,
        approvalId,
        strategy,
        traceId,
        null,
        null,
        null);
  }
}
