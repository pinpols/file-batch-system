package io.github.pinpols.batch.console.domain.job.application.contract.response;

import java.time.Instant;
import java.time.LocalDate;
import lombok.Builder;

@Builder
public record ConsoleJobInstanceResponse(
    Long id,
    String tenantId,
    String jobCode,
    String instanceNo,
    LocalDate bizDate,
    String triggerType,
    String instanceStatus,
    String batchNo,
    String operatorId,
    Boolean rerunFlag,
    Boolean retryFlag,
    String rerunReason,
    Long relatedFileId,
    Long parentInstanceId,
    String queueCode,
    String workerGroup,
    Integer priority,
    String traceId,
    String paramsSnapshot,
    String resultSummary,
    Instant deadlineAt,
    Integer expectedDurationSeconds,
    Instant slaAlertedAt,
    Instant startedAt,
    Instant finishedAt,
    /** ADR-026 dry-run 演练标记。 */
    Boolean dryRun,
    /** ADR-012 失败分类。 */
    String failureClass,
    /**
     * 根运行的触发类型。工作流节点派发的子实例 triggerType 恒为 EVENT，用户真正关心的手动 / 定时来源在根运行上；
     * 非工作流派发的实例为 null，此时以 triggerType 为准。
     */
    String rootTriggerType) {}
