package com.example.batch.common.tenant.routing;

import com.example.batch.common.mapper.BusinessTenantPlacementMapper;
import com.example.batch.common.persistence.entity.BusinessTenantPlacementEntity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link TenantPlacementRepository} 的 MyBatis 实现,经 {@link BusinessTenantPlacementMapper} 从 platform
 * 库 {@code batch.business_tenant_placement} 全量读 placement 映射。
 *
 * <p>表不存在(migration 未跑)或读失败时返回空 map 并打 WARN——让 {@link DbTablePlacementResolver} 退回 hash
 * 默认,路由不因维护表缺失而中断(fail-open 到算法默认,非 fail-closed)。
 */
@Slf4j
public final class MyBatisTenantPlacementRepository implements TenantPlacementRepository {

  private final BusinessTenantPlacementMapper mapper;

  public MyBatisTenantPlacementRepository(BusinessTenantPlacementMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Map<String, String> loadAll() {
    try {
      List<BusinessTenantPlacementEntity> rows = mapper.selectAll();
      Map<String, String> mapping = new LinkedHashMap<>();
      for (BusinessTenantPlacementEntity row : rows) {
        mapping.put(row.getTenantId(), row.getPlacementKey());
      }
      return mapping;
    } catch (RuntimeException ex) {
      log.warn(
          "load business_tenant_placement failed, fall back to hash placement: {}",
          ex.getMessage());
      return Map.of();
    }
  }
}
