package com.example.batch.console.service;

import com.example.batch.common.persistence.entity.BusinessTenantPlacementEntity;
import com.example.batch.console.domain.param.BusinessTenantPlacementUpsertParam;
import com.example.batch.console.mapper.ConsoleBusinessTenantPlacementMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * biz 租户分片 placement 管理(P2 tenant-routing 表驱动)。平台 ROLE_ADMIN 跨租维护: 列出全量映射、指派/迁片、取消指派(回退 hash)。
 *
 * <p>只写 {@code batch.business_tenant_placement}(租户→片,不含账密;凭据走 secrets)。worker 侧经 {@code
 * DbTablePlacementResolver} 按 TTL 缓存读取,迁片最迟一个 TTL 生效。
 */
@Service
@RequiredArgsConstructor
public class ConsoleBusinessTenantPlacementService {

  private final ConsoleBusinessTenantPlacementMapper placementMapper;

  public List<BusinessTenantPlacementEntity> list() {
    return placementMapper.findAll();
  }

  @Transactional
  public void upsert(BusinessTenantPlacementUpsertParam param) {
    placementMapper.upsert(param);
  }

  /** 取消指派(回退 hash);返回是否实际删除(幂等:不存在返回 false 不报错)。 */
  @Transactional
  public boolean delete(String tenantId) {
    return placementMapper.deleteByTenant(tenantId) > 0;
  }
}
