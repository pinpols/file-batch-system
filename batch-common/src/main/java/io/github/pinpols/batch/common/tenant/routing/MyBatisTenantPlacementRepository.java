package io.github.pinpols.batch.common.tenant.routing;

import io.github.pinpols.batch.common.mapper.BusinessTenantPlacementMapper;
import io.github.pinpols.batch.common.persistence.entity.BusinessTenantPlacementEntity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link TenantPlacementRepository} 的 MyBatis 实现,经 {@link BusinessTenantPlacementMapper} 从 platform
 * 库 {@code batch.business_tenant_placement} 全量读 placement 映射。
 *
 * <p>读失败<b>原样抛出</b>(不吞成空 map):由 {@link DbTablePlacementResolver} 决定降级语义——已有缓存则保留 stale(避免 silo 租户被
 * hash 误路由),冷启动无缓存才退 hash。把"空表"与"读失败"区分开是数据正确性关键。
 */
public final class MyBatisTenantPlacementRepository implements TenantPlacementRepository {

  private final BusinessTenantPlacementMapper mapper;

  public MyBatisTenantPlacementRepository(BusinessTenantPlacementMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Map<String, String> loadAll() {
    List<BusinessTenantPlacementEntity> rows = mapper.selectAll();
    Map<String, String> mapping = new LinkedHashMap<>();
    for (BusinessTenantPlacementEntity row : rows) {
      mapping.put(row.getTenantId(), row.getPlacementKey());
    }
    return mapping;
  }
}
