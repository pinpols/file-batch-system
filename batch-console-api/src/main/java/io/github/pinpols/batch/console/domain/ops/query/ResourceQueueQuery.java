package io.github.pinpols.batch.console.domain.ops.query;

import io.github.pinpols.batch.common.model.PageRequest;
import java.util.Objects;

/** 已解析租户的列表条件；计数与数据页复用同一组筛选，分页只由列表 SQL 使用。 */
public record ResourceQueueQuery(
    String tenantId, String queueCode, String queueType, Boolean enabled, PageRequest pageRequest) {

  public ResourceQueueQuery {
    Objects.requireNonNull(tenantId, "tenantId");
  }

  public static ResourceQueueQuery forTenant(String tenantId, PageRequest pageRequest) {
    Objects.requireNonNull(pageRequest, "pageRequest");
    return new ResourceQueueQuery(tenantId, null, null, null, pageRequest);
  }

  /** 配置包导出必须读取完整租户集合，不能套用交互列表的分页上限。 */
  public static ResourceQueueQuery allForTenant(String tenantId) {
    return new ResourceQueueQuery(tenantId, null, null, null, null);
  }

}
