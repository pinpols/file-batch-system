package io.github.pinpols.batch.orchestrator.infrastructure.file;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.util.Map;

/**
 * 到达组治理行的固定查询投影。
 *
 * <p>Mapper 仍以 Map 承接 MyBatis 行结果，但只在仓储边界转换一次；到达组调度、文件束 launch 与人工到达组操作读取字段不再依赖列名字符串。
 * {@code metadata_json} 是动态 JSON（文件束绑定键不固定），按具名字段原样保留，不拆成固定列。
 *
 * <p>可见性：{@link ArrivalCandidateView} 只被同包的调度与 launcher 消费，保持 package-private；到达组文件行与汇总行是仓储出参，
 * 由应用服务与真库集成测试直接读取访问器，故对应 record 与工厂方法为 public（视图落在仓储边界，避免公开应用服务内部类型）。
 */
public final class FileGovernanceArrivalViews {

  private FileGovernanceArrivalViews() {}

  /** {@code selectArrivalGovernanceCandidates} 出参视图：治理候选行，仅基础设施内部消费。 */
  static ArrivalCandidateView arrivalCandidate(Map<String, Object> row) {
    Long fileId = longValue(row.get("id"));
    String tenantId = textValue(row.get("tenant_id"));
    String fileName = textValue(row.get("file_name"));
    String bizDate = textValue(row.get("biz_date"));
    String fileGroupCode = textValue(row.get("file_group_code"));
    String waitFileGroupMode = textValue(row.get("wait_file_group_mode"));
    String requiredFileSet = textValue(row.get("required_file_set"));
    String arrivalTimeoutAction = textValue(row.get("arrival_timeout_action"));
    String arrivalState = textValue(row.get("arrival_state"));
    String arrivalReason = textValue(row.get("arrival_reason"));
    String latestTolerableTime = textValue(row.get("latest_tolerable_time"));
    String triggerOnComplete = textValue(row.get("trigger_on_complete"));
    String checksumType = textValue(row.get("checksum_type"));
    Object metadataJson = row.get("metadata_json");
    return new ArrivalCandidateView(
        fileId,
        tenantId,
        fileName,
        bizDate,
        fileGroupCode,
        waitFileGroupMode,
        requiredFileSet,
        arrivalTimeoutAction,
        arrivalState,
        arrivalReason,
        latestTolerableTime,
        triggerOnComplete,
        checksumType,
        metadataJson);
  }

  /** {@code selectArrivalGroupFiles} 出参视图：人工到达组操作只读取这几个列。 */
  public static ArrivalGroupFileView arrivalGroupFile(Map<String, Object> row) {
    return new ArrivalGroupFileView(
        longValue(row, "id"),
        stringValue(row, "biz_date"),
        booleanValue(row, "allow_empty_run"),
        booleanValue(row, "allow_skip_biz_date"),
        stringValue(row, "latest_tolerable_time"));
  }

  /** {@code selectArrivalGroupSummaries} 出参视图：到达组汇总行（计数列用于观测断言）。 */
  public static ArrivalGroupSummaryView arrivalGroupSummary(Map<String, Object> row) {
    return new ArrivalGroupSummaryView(
        stringValue(row, "tenant_id"),
        stringValue(row, "biz_date"),
        stringValue(row, "file_group_code"),
        stringValue(row, "wait_file_group_mode"),
        stringValue(row, "required_file_set"),
        stringValue(row, "arrival_timeout_action"),
        stringValue(row, "arrival_state"),
        stringValue(row, "expected_arrival_time"),
        stringValue(row, "latest_tolerable_time"),
        longValue(row, "arrived_count"),
        longValue(row, "triggered_count"),
        longValue(row, "timeout_count"),
        longValue(row, "waiting_count"),
        stringValue(row, "last_updated_at"));
  }

  static ArrivalGroupBacklogView arrivalGroupBacklog(Map<String, Object> row) {
    Long waitingGroups = longValue(row.get("waiting_groups"));
    Long oldestWaitSeconds = longValue(row.get("oldest_wait_seconds"));
    ArrivalGroupBacklogView backlog = new ArrivalGroupBacklogView(waitingGroups, oldestWaitSeconds);
    return backlog;
  }

  private static Long longValue(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (EmptyChecks.isNull(value)) {
      return null;
    }
    String presenceText = String.valueOf(value);
    if (EmptyChecks.isBlank(presenceText)) {
      return null;
    }
    String rawValue = String.valueOf(value);
    return Long.valueOf(rawValue);
  }

  private static String textValue(Object value) {
    return EmptyChecks.isNull(value) ? null : String.valueOf(value);
  }

  private static String stringValue(Map<String, Object> row, String key) {
    Object value = EmptyChecks.isNull(row) ? null : row.get(key);
    return EmptyChecks.isNull(value) ? null : value.toString();
  }

  private static Long longValue(Map<String, Object> row, String key) {
    Object value = EmptyChecks.isNull(row) ? null : row.get(key);
    return value instanceof Number number
        ? number.longValue()
        : EmptyChecks.isNull(value) ? null : Long.valueOf(value.toString());
  }

  private static boolean booleanValue(Map<String, Object> row, String key) {
    Object value = EmptyChecks.isNull(row) ? null : row.get(key);
    return value instanceof Boolean bool
        ? bool
        : EmptyChecks.isNotNull(value) && Boolean.parseBoolean(value.toString());
  }

  /**
   * 到达组候选行：{@code selectArrivalGovernanceCandidates} 的读取契约。
   *
   * <p>{@code checksumType} 对应该行完整性背书，供 {@code require-verified} 判定；{@code metadataJson} 为动态
   * JSON，交由文件束 launch 解析 bundleJobCode 等扩展键。
   */
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
