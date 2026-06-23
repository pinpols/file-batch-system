package io.github.pinpols.batch.console.infrastructure.excel;

import static io.github.pinpols.batch.console.infrastructure.excel.AbstractSingleSheetExcelService.optionalBoolean;
import static io.github.pinpols.batch.console.infrastructure.excel.AbstractSingleSheetExcelService.optionalEnum;
import static io.github.pinpols.batch.console.infrastructure.excel.AbstractSingleSheetExcelService.optionalText;
import static io.github.pinpols.batch.console.infrastructure.excel.AbstractSingleSheetExcelService.requireEnum;
import static io.github.pinpols.batch.console.infrastructure.excel.AbstractSingleSheetExcelService.requireInteger;
import static io.github.pinpols.batch.console.infrastructure.excel.AbstractSingleSheetExcelService.requireText;
import static io.github.pinpols.batch.console.infrastructure.excel.AbstractSingleSheetExcelService.resolveTenantField;

import io.github.pinpols.batch.common.enums.DictEnum;
import io.github.pinpols.batch.common.enums.QueuePriorityPolicy;
import io.github.pinpols.batch.common.enums.ResourceQueueType;
import io.github.pinpols.batch.common.utils.ConsoleTextSanitizer;
import io.github.pinpols.batch.console.domain.param.ResourceQueueUpsertParam;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Builder;

/** Shared parser/upsert helper for resource_queue Excel rows. */
public final class ResourceQueueExcelRowParser {

  public static final String SHEET_NAME = "resource_queue";

  private static final Set<String> QUEUE_TYPES = DictEnum.codes(ResourceQueueType.class);
  private static final Set<String> PRIORITY_POLICIES = DictEnum.codes(QueuePriorityPolicy.class);

  private ResourceQueueExcelRowParser() {}

  public static QueueRow parseRow(
      String tenantId, int rowNo, Map<String, String> values, List<String> issues) {
    String effectiveTenant = resolveTenantField(values, tenantId, issues);
    QueueRow row =
        QueueRow.builder()
            .rowNo(rowNo)
            .tenantId(effectiveTenant)
            .queueCode(requireText(values, "queue_code", 128, issues))
            .queueName(requireText(values, "queue_name", 256, issues))
            .queueType(requireEnum(values, "queue_type", QUEUE_TYPES, 32, issues))
            .maxRunningJobs(requireInteger(values, "max_running_jobs", 0, issues))
            .maxRunningPartitions(requireInteger(values, "max_running_partitions", 0, issues))
            .maxQps(requireInteger(values, "max_qps", 0, issues))
            .workerGroup(optionalText(values, "worker_group", 128, issues))
            .resourceTag(optionalText(values, "resource_tag", 64, issues))
            .priorityPolicy(
                optionalEnum(values, "priority_policy", PRIORITY_POLICIES, 32, "FIFO", issues))
            .fairShareWeight(requireInteger(values, "fair_share_weight", 1, issues))
            .enabled(optionalBoolean(values, "enabled", true, issues))
            .description(optionalText(values, "description", 512, issues))
            .build();
    return row;
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
    param.setCreatedBy(ConsoleTextSanitizer.safeInput(operatorId, 64));
    param.setUpdatedBy(ConsoleTextSanitizer.safeInput(operatorId, 64));
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
