package com.example.batch.console.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.config.BusinessRoutingProperties;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.persistence.entity.BusinessTenantPlacementEntity;
import com.example.batch.console.domain.param.BusinessTenantPlacementUpsertParam;
import com.example.batch.console.mapper.ConsoleBusinessTenantPlacementMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConsoleBusinessTenantPlacementServiceTest {

  @Mock private ConsoleBusinessTenantPlacementMapper placementMapper;

  private ConsoleBusinessTenantPlacementService service(BusinessRoutingProperties routing) {
    return new ConsoleBusinessTenantPlacementService(placementMapper, routing);
  }

  private static BusinessRoutingProperties shards(String... keys) {
    BusinessRoutingProperties props = new BusinessRoutingProperties();
    for (String key : keys) {
      BusinessRoutingProperties.Shard shard = new BusinessRoutingProperties.Shard();
      shard.setKey(key);
      props.getShards().add(shard);
    }
    return props;
  }

  @Test
  void listShouldDelegateToMapper() {
    BusinessTenantPlacementEntity row = new BusinessTenantPlacementEntity();
    row.setTenantId("t-1");
    row.setPlacementKey("silo-big");
    when(placementMapper.findAll()).thenReturn(List.of(row));

    assertThat(service(new BusinessRoutingProperties()).list())
        .singleElement()
        .extracting(BusinessTenantPlacementEntity::getPlacementKey)
        .isEqualTo("silo-big");
    verify(placementMapper).findAll();
  }

  @Test
  void upsertShouldDelegateToMapper_whenNoShardsConfigured() {
    BusinessTenantPlacementUpsertParam param =
        BusinessTenantPlacementUpsertParam.builder()
            .tenantId("t-1")
            .placementKey("shard-1")
            .operator("ops:alice")
            .build();
    // 未配 shards → 不校验,直接落库(运行时 lenientFallback=false 兜底)
    service(new BusinessRoutingProperties()).upsert(param);
    verify(placementMapper).upsert(param);
  }

  @Test
  void upsertShouldAcceptConfiguredKey() {
    BusinessTenantPlacementUpsertParam param =
        BusinessTenantPlacementUpsertParam.builder()
            .tenantId("t-1")
            .placementKey("silo-big")
            .operator("ops:alice")
            .build();
    service(shards("shard-0", "shard-1", "silo-big")).upsert(param);
    verify(placementMapper).upsert(param);
  }

  @Test
  void upsertShouldRejectUnknownKey_whenShardsConfigured() {
    BusinessTenantPlacementUpsertParam param =
        BusinessTenantPlacementUpsertParam.builder()
            .tenantId("t-1")
            .placementKey("silo-typo")
            .operator("ops:alice")
            .build();
    assertThatThrownBy(() -> service(shards("shard-0", "shard-1")).upsert(param))
        .isInstanceOf(BizException.class);
  }

  @Test
  void deleteShouldReportWhetherRemoved() {
    when(placementMapper.deleteByTenant("t-1")).thenReturn(1);
    when(placementMapper.deleteByTenant("t-absent")).thenReturn(0);

    ConsoleBusinessTenantPlacementService svc = service(new BusinessRoutingProperties());
    assertThat(svc.delete("t-1")).isTrue();
    assertThat(svc.delete("t-absent")).isFalse();
  }
}
