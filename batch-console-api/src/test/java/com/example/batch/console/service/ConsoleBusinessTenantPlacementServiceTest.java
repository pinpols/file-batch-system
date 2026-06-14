package com.example.batch.console.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.config.BusinessRoutingProperties;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.persistence.entity.BusinessTenantPlacementEntity;
import com.example.batch.console.domain.param.BusinessTenantPlacementUpsertParam;
import com.example.batch.console.mapper.ConsoleBusinessShardCatalogMapper;
import com.example.batch.console.mapper.ConsoleBusinessTenantPlacementMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConsoleBusinessTenantPlacementServiceTest {

  @Mock private ConsoleBusinessTenantPlacementMapper placementMapper;
  @Mock private ConsoleBusinessShardCatalogMapper shardCatalogMapper;

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
  void listShouldDelegateToMapper() {
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
  void upsertShouldProceed_whenNoCatalogOrShardsConfigured() {
    when(shardCatalogMapper.findEnabledKeys()).thenReturn(List.of());
    BusinessTenantPlacementUpsertParam p = param("shard-1");
    service().upsert(p);
    verify(placementMapper).upsert(p);
  }

  @Test
  void upsertShouldAcceptKeyInCatalog() {
    when(shardCatalogMapper.findEnabledKeys())
        .thenReturn(List.of("shard-0", "shard-1", "silo-big"));
    BusinessTenantPlacementUpsertParam p = param("silo-big");
    service().upsert(p);
    verify(placementMapper).upsert(p);
  }

  @Test
  void upsertShouldRejectKeyNotInCatalog() {
    when(shardCatalogMapper.findEnabledKeys()).thenReturn(List.of("shard-0", "shard-1"));
    assertThatThrownBy(() -> service().upsert(param("silo-typo"))).isInstanceOf(BizException.class);
  }

  @Test
  void deleteShouldReportWhetherRemoved() {
    when(placementMapper.deleteByTenant("t-1")).thenReturn(1);
    when(placementMapper.deleteByTenant("t-absent")).thenReturn(0);

    ConsoleBusinessTenantPlacementService svc = service();
    assertThat(svc.delete("t-1")).isTrue();
    assertThat(svc.delete("t-absent")).isFalse();
  }
}
