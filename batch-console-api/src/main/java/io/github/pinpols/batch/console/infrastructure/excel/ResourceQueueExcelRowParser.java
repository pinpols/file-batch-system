package io.github.pinpols.batch.console.infrastructure.excel;

import static io.github.pinpols.batch.console.infrastructure.excel.SingleSheetExcelCellParsers.optionalBoolean;
import static io.github.pinpols.batch.console.infrastructure.excel.SingleSheetExcelCellParsers.optionalEnum;
import static io.github.pinpols.batch.console.infrastructure.excel.SingleSheetExcelCellParsers.optionalText;
import static io.github.pinpols.batch.console.infrastructure.excel.SingleSheetExcelCellParsers.requireEnum;
import static io.github.pinpols.batch.console.infrastructure.excel.SingleSheetExcelCellParsers.requireInteger;
import static io.github.pinpols.batch.console.infrastructure.excel.SingleSheetExcelCellParsers.requireText;
import static io.github.pinpols.batch.console.infrastructure.excel.SingleSheetExcelCellParsers.resolveTenantField;

import io.github.pinpols.batch.common.enums.DictEnum;
import io.github.pinpols.batch.common.enums.QueuePriorityPolicy;
import io.github.pinpols.batch.common.enums.ResourceQueueType;
import io.github.pinpols.batch.common.utils.CodeNormalizer;
import io.github.pinpols.batch.common.utils.ConsoleTextSanitizer;
import io.github.pinpols.batch.console.domain.ops.param.ResourceQueueUpsertParam;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Builder;

/** resource_queue Excel 行共用的解析与新增或更新辅助逻辑。 */
public final class ResourceQueueExcelRowParser {

  /** resource_queue.queue_code VARCHAR(128)（V3__create_config_tables.sql）。 */
  private static final int QUEUE_CODE_MAX_LENGTH = 128;

  /** resource_queue.queue_name VARCHAR(256)（V3__create_config_tables.sql）。 */
  private static final int QUEUE_NAME_MAX_LENGTH = 256;

  /** resource_queue.queue_type VARCHAR(32)（V3__create_config_tables.sql）。 */
  private static final int QUEUE_TYPE_MAX_LENGTH = 32;

  /** resource_queue.worker_group VARCHAR(128)（V3__create_config_tables.sql）。 */
  private static final int WORKER_GROUP_MAX_LENGTH = 128;

  /** resource_queue.resource_tag VARCHAR(64)（V3__create_config_tables.sql）。 */
  private static final int RESOURCE_TAG_MAX_LENGTH = 64;

  /** resource_queue.priority_policy VARCHAR(32)（V3__create_config_tables.sql）。 */
  private static final int PRIORITY_POLICY_MAX_LENGTH = 32;

  /** resource_queue.description VARCHAR(512)（V3__create_config_tables.sql）。 */
  private static final int DESCRIPTION_MAX_LENGTH = 512;

  /**
   * 操作人 ID 截断长度，与其他配置表 created_by / updated_by 的 VARCHAR(64) 口径一致
   * （resource_queue 表本身没有这两列）。
   */
  private static final int OPERATOR_ID_MAX_LENGTH = 64;

  public static final String SHEET_NAME = "resource_queue";

  private static final Set<String> QUEUE_TYPES = DictEnum.codes(ResourceQueueType.class);
  private static final Set<String> PRIORITY_POLICIES = DictEnum.codes(QueuePriorityPolicy.class);

  private ResourceQueueExcelRowParser() {}

  public static QueueRow parseRow(
      String tenantId, int rowNo, Map<String, String> values, List<String> issues) {
    String effectiveTenant = resolveTenantField(values, tenantId, issues);
    QueueRow.QueueRowBuilder rowBuilder = QueueRow.builder();
    rowBuilder.rowNo(rowNo);
    rowBuilder.tenantId(effectiveTenant);
    String queueCodeText = requireText(values, "queue_code", QUEUE_CODE_MAX_LENGTH, issues);
    String queueCode = CodeNormalizer.toConfigFormOrNull(queueCodeText);
    rowBuilder.queueCode(queueCode);
    String queueName = requireText(values, "queue_name", QUEUE_NAME_MAX_LENGTH, issues);
    rowBuilder.queueName(queueName);
    String queueType =
        requireEnum(values, "queue_type", QUEUE_TYPES, QUEUE_TYPE_MAX_LENGTH, issues);
    rowBuilder.queueType(queueType);
    Integer maxRunningJobs = requireInteger(values, "max_running_jobs", 0, issues);
    rowBuilder.maxRunningJobs(maxRunningJobs);
    Integer maxRunningPartitions = requireInteger(values, "max_running_partitions", 0, issues);
    rowBuilder.maxRunningPartitions(maxRunningPartitions);
    Integer maxQps = requireInteger(values, "max_qps", 0, issues);
    rowBuilder.maxQps(maxQps);
    String workerGroup = optionalText(values, "worker_group", WORKER_GROUP_MAX_LENGTH, issues);
    rowBuilder.workerGroup(workerGroup);
    String resourceTag = optionalText(values, "resource_tag", RESOURCE_TAG_MAX_LENGTH, issues);
    rowBuilder.resourceTag(resourceTag);
    String priorityPolicy = optionalEnum(
        values, "priority_policy", PRIORITY_POLICIES, PRIORITY_POLICY_MAX_LENGTH, "FIFO", issues);
    rowBuilder.priorityPolicy(priorityPolicy);
    Integer fairShareWeight = requireInteger(values, "fair_share_weight", 1, issues);
    rowBuilder.fairShareWeight(fairShareWeight);
    Boolean enabled = optionalBoolean(values, "enabled", true, issues);
    rowBuilder.enabled(enabled);
    String description = optionalText(values, "description", DESCRIPTION_MAX_LENGTH, issues);
    rowBuilder.description(description);
    return rowBuilder.build();
  }

  public static ResourceQueueUpsertParam toUpsertParam(QueueRow row, String operatorId) {
    ResourceQueueUpsertParam param = new ResourceQueueUpsertParam();
    param.setTenantId(row.tenantId());
    param.setQueueCode(row.queueCode());
    param.setQueueName(row.queueName());
    param.setQueueType(row.queueType());
    param.setMaxRunningJobs(row.maxRunningJobs());
    param.setMaxRunningPartitions(row.maxRunningPartitions());
    param.setMaxQps(row.maxQps());
    param.setWorkerGroup(row.workerGroup());
    param.setResourceTag(row.resourceTag());
    param.setPriorityPolicy(row.priorityPolicy());
    param.setFairShareWeight(row.fairShareWeight());
    param.setEnabled(row.enabled());
    param.setDescription(row.description());
    param.setCreatedBy(ConsoleTextSanitizer.safeInput(operatorId, OPERATOR_ID_MAX_LENGTH));
    param.setUpdatedBy(ConsoleTextSanitizer.safeInput(operatorId, OPERATOR_ID_MAX_LENGTH));
    return param;
  }

  @Builder
  public record QueueRow(
      int rowNo,
      String tenantId,
      String queueCode,
      String queueName,
      String queueType,
      Integer maxRunningJobs,
      Integer maxRunningPartitions,
      Integer maxQps,
      String workerGroup,
      String resourceTag,
      String priorityPolicy,
      Integer fairShareWeight,
      Boolean enabled,
      String description) {}
}
