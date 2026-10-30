package io.github.pinpols.batch.console.domain.ops.view;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * batch.resource_queue 行投影（ResourceQueueMapper 全表 20 列具名 record）。
 *
 * <p>selectByQuery 的 list / Excel 导出查询只选择 16 列，其余 4 个 V16 调度列（fair_share_group、
 * burst_limit、quota_reset_policy、group_shared_max_running_jobs）以 {@code cast(null as ...)} 补齐构造器
 * 参数；selectById / selectByUniqueKey 为全列。
 *
 * <p>{@link #toMap()} 返回 Excel 导出使用的 snake_case 投影，16 个键均保留，值可为 null；工作簿按自身列定义选取字段。
 */
public record ResourceQueueRow(
    Long id,
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
    String description,
    Instant createdAt,
    Instant updatedAt,
    String fairShareGroup,
    Integer burstLimit,
    String quotaResetPolicy,
    Integer groupSharedMaxRunningJobs) {

  /**
   * Excel 导出工作簿行投影：包含 16 个 snake_case 键（值可为 null）。
   *
   * <p>工作簿按列定义从此投影读取值；键缺失或值为 null 时该单元格为空。
   */
  public Map<String, Object> toMap() {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", id);
    map.put("tenant_id", tenantId);
    map.put("queue_code", queueCode);
    map.put("queue_name", queueName);
    map.put("queue_type", queueType);
    map.put("max_running_jobs", maxRunningJobs);
    map.put("max_running_partitions", maxRunningPartitions);
    map.put("max_qps", maxQps);
    map.put("worker_group", workerGroup);
    map.put("resource_tag", resourceTag);
    map.put("priority_policy", priorityPolicy);
    map.put("fair_share_weight", fairShareWeight);
    map.put("enabled", enabled);
    map.put("description", description);
    map.put("created_at", createdAt);
    map.put("updated_at", updatedAt);
    return map;
  }
}
