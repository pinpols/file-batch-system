package io.github.pinpols.batch.console.domain.ops.query;

import io.github.pinpols.batch.common.model.PageRequest;
import java.util.Objects;

/** 已解析租户的列表条件；计数与数据页复用同一组筛选，分页只由列表 SQL 使用。 */
public record ResourceQueueQuery(
    String tenantId, String queueCode, String queueType, Boolean enabled, PageRequest pageRequest) {

  public ResourceQueueQuery {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(pageRequest, "pageRequest");
  }

  public static ResourceQueueQuery forTenant(String tenantId, PageRequest pageRequest) {
    return new ResourceQueueQuery(tenantId, null, null, null, pageRequest);
  }
}
