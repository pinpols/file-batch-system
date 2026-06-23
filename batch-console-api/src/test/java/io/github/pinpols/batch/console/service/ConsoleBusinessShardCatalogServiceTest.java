package io.github.pinpols.batch.console.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.console.domain.entity.BusinessShardCatalogEntity;
import io.github.pinpols.batch.console.domain.param.BusinessShardCatalogUpsertParam;
import io.github.pinpols.batch.console.mapper.ConsoleBusinessShardCatalogMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConsoleBusinessShardCatalogServiceTest {

  @Mock private ConsoleBusinessShardCatalogMapper catalogMapper;
  @InjectMocks private ConsoleBusinessShardCatalogService service;

  @Test
  void listShouldDelegate() {
    BusinessShardCatalogEntity row = new BusinessShardCatalogEntity();
    row.setPlacementKey("shard-1");
    when(catalogMapper.findAll()).thenReturn(List.of(row));
    assertThat(service.list())
        .singleElement()
        .extracting(BusinessShardCatalogEntity::getPlacementKey)
        .isEqualTo("shard-1");
  }

  @Test
  void enabledKeysShouldDelegate() {
    when(catalogMapper.findEnabledKeys()).thenReturn(List.of("shard-0", "shard-1"));
    assertThat(service.enabledKeys()).containsExactly("shard-0", "shard-1");
  }

  @Test
  void upsertShouldDelegate() {
    BusinessShardCatalogUpsertParam p =
        BusinessShardCatalogUpsertParam.builder()
            .placementKey("shard-1")
            .host("db-1")
            .port(5432)
            .dbName("batch_business")
            .enabled(true)
            .operator("ops:bob")
            .build();
    service.upsert(p);
    verify(catalogMapper).upsert(p);
  }

  @Test
  void deleteShouldReportWhetherRemoved() {
    when(catalogMapper.deleteByKey("shard-9")).thenReturn(1);
    assertThat(service.delete("shard-9")).isTrue();
  }
}
