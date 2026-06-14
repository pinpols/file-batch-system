package com.example.batch.console.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.persistence.entity.BusinessTenantPlacementEntity;
import com.example.batch.console.domain.param.BusinessTenantPlacementUpsertParam;
import com.example.batch.console.mapper.ConsoleBusinessTenantPlacementMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConsoleBusinessTenantPlacementServiceTest {

  @Mock private ConsoleBusinessTenantPlacementMapper placementMapper;
  @InjectMocks private ConsoleBusinessTenantPlacementService service;

  @Test
  void listShouldDelegateToMapper() {
    BusinessTenantPlacementEntity row = new BusinessTenantPlacementEntity();
    row.setTenantId("t-1");
    row.setPlacementKey("silo-big");
    when(placementMapper.findAll()).thenReturn(List.of(row));

    assertThat(service.list())
        .singleElement()
        .extracting(BusinessTenantPlacementEntity::getPlacementKey)
        .isEqualTo("silo-big");
    verify(placementMapper).findAll();
  }

  @Test
  void upsertShouldDelegateToMapper() {
    BusinessTenantPlacementUpsertParam param =
        BusinessTenantPlacementUpsertParam.builder()
            .tenantId("t-1")
            .placementKey("shard-1")
            .operator("ops:alice")
            .build();
    service.upsert(param);
    verify(placementMapper).upsert(param);
  }

  @Test
  void deleteShouldReportWhetherRemoved() {
    when(placementMapper.deleteByTenant("t-1")).thenReturn(1);
    when(placementMapper.deleteByTenant("t-absent")).thenReturn(0);

    assertThat(service.delete("t-1")).isTrue();
    assertThat(service.delete("t-absent")).isFalse();
  }
}
