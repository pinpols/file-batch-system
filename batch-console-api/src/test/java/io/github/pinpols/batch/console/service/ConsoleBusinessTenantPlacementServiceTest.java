package io.github.pinpols.batch.console.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BusinessRoutingProperties;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.BusinessTenantPlacementEntity;
import io.github.pinpols.batch.console.domain.param.BusinessTenantPlacementUpsertParam;
import io.github.pinpols.batch.console.mapper.ConsoleBusinessShardCatalogMapper;
import io.github.pinpols.batch.console.mapper.ConsoleBusinessTenantPlacementMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("业务租户放置服务: 列表查询、启用分片目录校验与删除结果判定")
class ConsoleBusinessTenantPlacementServiceTest {

  @Mock
  private ConsoleBusinessTenantPlacementMapper placementMapper;

  @Mock
  private ConsoleBusinessShardCatalogMapper shardCatalogMapper;

  private ConsoleBusinessTenantPlacementService service() {
    return new ConsoleBusinessTenantPlacementService(
        placementMapper, shardCatalogMapper, new BusinessRoutingProperties());
  }

  private static BusinessTenantPlacementUpsertParam param(String key) {
    return BusinessTenantPlacementUpsertParam.builder()
        .tenantId("t-1")
        .placementKey(key)
        .operator("ops:alice")
        .build();
  }

  @Test
  @DisplayName("查询全部租户放置时, 返回仓储给出的放置键")
  void shouldReturnPlacements_whenListingAll() {
    BusinessTenantPlacementEntity row = new BusinessTenantPlacementEntity();
    row.setTenantId("t-1");
    row.setPlacementKey("silo-big");
    when(placementMapper.findAll()).thenReturn(List.of(row));

    assertThat(service().list())
        .singleElement()
        .extracting(BusinessTenantPlacementEntity::getPlacementKey)
        .isEqualTo("silo-big");
    verify(placementMapper).findAll();
  }

  @Test
  @DisplayName("启用分片目录为空时, 放置写入不做额外校验直接落库")
  void upsertShouldProceed_whenNoCatalogOrShardsConfigured() {
    when(shardCatalogMapper.findEnabledKeys()).thenReturn(List.of());
    BusinessTenantPlacementUpsertParam p = param("shard-1");
    service().upsert(p);
    verify(placementMapper).upsert(p);
  }

  @Test
  @DisplayName("放置键存在于启用分片目录内时, 写入成功落库")
  void shouldAcceptPlacementKey_whenKeyInCatalog() {
    when(shardCatalogMapper.findEnabledKeys())
        .thenReturn(List.of("shard-0", "shard-1", "silo-big"));
    BusinessTenantPlacementUpsertParam p = param("silo-big");
    service().upsert(p);
    verify(placementMapper).upsert(p);
  }

  @Test
  @DisplayName("放置键不在启用分片目录内时, 抛业务异常拒绝写入")
  void shouldRejectPlacementKey_whenKeyNotInCatalog() {
    when(shardCatalogMapper.findEnabledKeys()).thenReturn(List.of("shard-0", "shard-1"));
    assertThatThrownBy(() -> service().upsert(param("silo-typo"))).isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("删除租户放置时, 按仓储受影响行数返回删除是否成功")
  void shouldReportRemoved_whenDeletingPlacement() {
    when(placementMapper.deleteByTenant("t-1")).thenReturn(1);
    when(placementMapper.deleteByTenant("t-absent")).thenReturn(0);

    ConsoleBusinessTenantPlacementService svc = service();
    assertThat(svc.delete("t-1")).isTrue();
    assertThat(svc.delete("t-absent")).isFalse();
  }
}
