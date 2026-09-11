package io.github.pinpols.batch.orchestrator.domain.command;

import java.time.LocalDate;
import java.util.Map;
import lombok.Builder;

@Builder
@SuppressWarnings("PMD.ExcessiveParameterList")
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
    Integer configVersion,
    /** ADR-020 batch_day_replay_session.id 标签；NULL = 非 replay 创建。 */
    Long replaySessionId,
    /** replay entry 稳定标识；用于崩溃恢复时复用同一 launch 幂等键。 */
    Long replayEntryId,
    /** SCHEDULE_PLAN replay 固化的启动参数；普通补偿为空。 */
    Map<String, Object> launchParams,
    /** ADR-026：补偿产生的实例是否为无业务副作用演练。 */
    Boolean dryRun) {

  /** 兼容旧测试与历史调用的 14-arg 构造，rerun policy + replay 字段补 null。 */
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
        null,
        null,
        null,
        null,
        false);
  }

  /** 17 参兼容构造：未带 replay session 标签的旧路径自动补 null。 */
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
      String traceId,
      String resultPolicy,
      String configVersionPolicy,
      Integer configVersion) {
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
        resultPolicy,
        configVersionPolicy,
        configVersion,
        null,
        null,
        null,
        false);
  }

  /** 18 参兼容构造：已有批量日重放路径默认正式执行。 */
  @SuppressWarnings("PMD.ExcessiveParameterList")
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
      String traceId,
      String resultPolicy,
      String configVersionPolicy,
      Integer configVersion,
      Long replaySessionId) {
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
        resultPolicy,
        configVersionPolicy,
        configVersion,
        replaySessionId,
        null,
        null,
        false);
  }
}
