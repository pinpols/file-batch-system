package io.github.pinpols.batch.orchestrator.infrastructure.file;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.orchestrator.domain.view.FileGovernanceViews;
import lombok.Builder;

/** 到达组治理的固定应用视图；metadata_json 仅作为动态 JSON 字段透传。 */
public final class FileGovernanceArrivalViews {

  private FileGovernanceArrivalViews() {}

  /** {@code selectArrivalGovernanceCandidates} 出参视图：治理候选行，仅基础设施内部消费。 */
  static ArrivalCandidateView arrivalCandidate(FileGovernanceViews.ArrivalCandidateRow row) {
    String bizDate = textValue(row.bizDate());
    return new ArrivalCandidateView(
        row.id(),
        row.tenantId(),
        row.fileName(),
        bizDate,
        row.fileGroupCode(),
        row.waitFileGroupMode(),
        row.requiredFileSet(),
        row.arrivalTimeoutAction(),
        row.arrivalState(),
        row.arrivalReason(),
        row.latestTolerableTime(),
        row.triggerOnComplete(),
        row.checksumType(),
        row.metadataJson());
  }

  public static ArrivalGroupFileView arrivalGroupFile(FileGovernanceViews.ArrivalGroupFileRow row) {
    String bizDate = textValue(row.bizDate());
    boolean allowEmptyRun = Boolean.parseBoolean(row.allowEmptyRun());
    boolean allowSkipBizDate = Boolean.parseBoolean(row.allowSkipBizDate());
    return new ArrivalGroupFileView(
        row.id(), bizDate, allowEmptyRun, allowSkipBizDate, row.latestTolerableTime());
  }

  public static ArrivalGroupSummaryView arrivalGroupSummary(
      FileGovernanceViews.ArrivalGroupSummaryRow row) {
    return ArrivalGroupSummaryView.builder()
        .tenantId(row.tenantId())
        .bizDate(textValue(row.bizDate()))
        .fileGroupCode(row.fileGroupCode())
        .waitFileGroupMode(row.waitFileGroupMode())
        .requiredFileSet(row.requiredFileSet())
        .arrivalTimeoutAction(row.arrivalTimeoutAction())
        .arrivalState(row.arrivalState())
        .expectedArrivalTime(row.expectedArrivalTime())
        .latestTolerableTime(row.latestTolerableTime())
        .arrivedCount(row.arrivedCount())
        .triggeredCount(row.triggeredCount())
        .timeoutCount(row.timeoutCount())
        .waitingCount(row.waitingCount())
        .lastUpdatedAt(textValue(row.lastUpdatedAt()))
        .build();
  }

  static ArrivalGroupBacklogView arrivalGroupBacklog(
      FileGovernanceViews.ArrivalGroupBacklogRow row) {
    Long waitingGroups = row.waitingGroups();
    Long oldestWaitSeconds = row.oldestWaitSeconds();
    ArrivalGroupBacklogView backlog = new ArrivalGroupBacklogView(waitingGroups, oldestWaitSeconds);
    return backlog;
  }

  private static String textValue(Object value) {
    return EmptyChecks.isNull(value) ? null : String.valueOf(value);
  }

  /**
   * 到达组候选行：{@code selectArrivalGovernanceCandidates} 的读取契约。
   *
   * <p>{@code checksumType} 对应该行完整性背书，供 {@code require-verified} 判定；{@code metadataJson} 为动态
   * JSON，交由文件束 launch 解析 bundleJobCode 等扩展键。
   */
  @Builder
  record ArrivalCandidateView(
      Long fileId,
      String tenantId,
      String fileName,
      String bizDate,
      String fileGroupCode,
      String waitFileGroupMode,
      String requiredFileSet,
      String arrivalTimeoutAction,
      String arrivalState,
      String arrivalReason,
      String latestTolerableTime,
      String triggerOnComplete,
      String checksumType,
      Object metadataJson) {}

  /** 人工到达组文件行：允许空跑 / 跳过业务日开关 + 容忍时间。 */
  public record ArrivalGroupFileView(
      Long fileId,
      String bizDate,
      boolean allowEmptyRun,
      boolean allowSkipBizDate,
      String latestTolerableTime) {}

  record ArrivalGroupBacklogView(Long waitingGroups, Long oldestWaitSeconds) {}

  /** 到达组汇总行：分组维度 + 到达/触发/超时/等待计数。 */
  @Builder
  public record ArrivalGroupSummaryView(
      String tenantId,
      String bizDate,
      String fileGroupCode,
      String waitFileGroupMode,
      String requiredFileSet,
      String arrivalTimeoutAction,
      String arrivalState,
      String expectedArrivalTime,
      String latestTolerableTime,
      Long arrivedCount,
      Long triggeredCount,
      Long timeoutCount,
      Long waitingCount,
      String lastUpdatedAt) {}
}
