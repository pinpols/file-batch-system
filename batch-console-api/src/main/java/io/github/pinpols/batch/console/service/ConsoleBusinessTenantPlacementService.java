package io.github.pinpols.batch.console.service;

import io.github.pinpols.batch.common.config.BusinessRoutingProperties;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.BusinessTenantPlacementEntity;
import io.github.pinpols.batch.console.domain.param.BusinessTenantPlacementUpsertParam;
import io.github.pinpols.batch.console.mapper.ConsoleBusinessShardCatalogMapper;
import io.github.pinpols.batch.console.mapper.ConsoleBusinessTenantPlacementMapper;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * biz 租户分片 placement 管理(P2 tenant-routing 表驱动)。平台 ROLE_ADMIN 跨租维护: 列出全量映射、指派/迁片、取消指派(回退 hash)。
 *
 * <p>只写 {@code batch.business_tenant_placement}(租户→片,不含账密;凭据走 secrets)。worker 侧经 {@code
 * DbTablePlacementResolver} 按 TTL 缓存读取,迁片最迟一个 TTL 生效。
 *
 * <p>upsert 时若 console 侧已配 {@code batch.datasource.business.routing.shards}(片拓扑),校验目标 key 在已配置
 * 片集合内,**提前**拦掉 typo(把租户指到不存在的片)。未配拓扑时跳过本校验——worker 侧 multiShard 关了 lenientFallback,运行时仍会对未知 key
 * 硬失败回退。
 */
@Service
@RequiredArgsConstructor
public class ConsoleBusinessTenantPlacementService {

  private final ConsoleBusinessTenantPlacementMapper placementMapper;
  private final ConsoleBusinessShardCatalogMapper shardCatalogMapper;
  private final BusinessRoutingProperties routingProperties;

  public List<BusinessTenantPlacementEntity> list() {
    return placementMapper.findAll();
  }

  @Transactional
  public void upsert(BusinessTenantPlacementUpsertParam param) {
    Set<String> configuredKeys = configuredShardKeys();
    if (!configuredKeys.isEmpty() && !configuredKeys.contains(param.placementKey())) {
      throw BizException.of(
          ResultCode.VALIDATION_ERROR,
          "error.tenant_placement.unknown_key",
          param.placementKey(),
          String.join(",", configuredKeys));
    }
    placementMapper.upsert(param);
  }

  /** 取消指派(回退 hash);返回是否实际删除(幂等:不存在返回 false 不报错)。 */
  @Transactional
  public boolean delete(String tenantId) {
    return placementMapper.deleteByTenant(tenantId) > 0;
  }

  /**
   * placement 指派的合法 key 集合,优先级:shard catalog(enabled 片,权威)→ routing.shards 配置 → 空(不校验)。
   * 空集合表示无可校验源,跳过(运行时 lenientFallback=false 回退)。
   */
  private Set<String> configuredShardKeys() {
    Set<String> keys = new LinkedHashSet<>(shardCatalogMapper.findEnabledKeys());
    if (!keys.isEmpty()) {
      return keys;
    }
    for (BusinessRoutingProperties.Shard shard : routingProperties.getShards()) {
      if (shard.getKey() != null && !shard.getKey().isBlank()) {
        keys.add(shard.getKey());
      }
    }
    return keys;
  }
}
