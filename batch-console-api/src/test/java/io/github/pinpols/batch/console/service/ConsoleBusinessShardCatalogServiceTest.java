package io.github.pinpols.batch.console.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.console.domain.entity.BusinessShardCatalogEntity;
import io.github.pinpols.batch.console.domain.param.BusinessShardCatalogUpsertParam;
import io.github.pinpols.batch.console.mapper.ConsoleBusinessShardCatalogMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("业务分片目录服务: 列表查询、启用键查询、写入与删除的仓储委托")
class ConsoleBusinessShardCatalogServiceTest {

  @Mock
  private ConsoleBusinessShardCatalogMapper catalogMapper;

  @InjectMocks
  private ConsoleBusinessShardCatalogService service;

  @Test
  @DisplayName("查询全部分片目录时, 返回仓储给出的分片键")
  void shouldReturnCatalogRows_whenListingAll() {
    BusinessShardCatalogEntity row = new BusinessShardCatalogEntity();
    row.setPlacementKey("shard-1");
    when(catalogMapper.findAll()).thenReturn(List.of(row));
    assertThat(service.list())
        .singleElement()
        .extracting(BusinessShardCatalogEntity::getPlacementKey)
        .isEqualTo("shard-1");
  }

  @Test
  @DisplayName("查询已启用的分片键时, 返回仓储给出的键列表")
  void shouldReturnEnabledKeys_whenQueryingEnabled() {
    when(catalogMapper.findEnabledKeys()).thenReturn(List.of("shard-0", "shard-1"));
    assertThat(service.enabledKeys()).containsExactly("shard-0", "shard-1");
  }

  @Test
  @DisplayName("写入分片目录时, 入参原样交给仓储")
  void shouldDelegateUpsert_whenSavingCatalogRow() {
    BusinessShardCatalogUpsertParam p = BusinessShardCatalogUpsertParam.builder()
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
  @DisplayName("删除分片目录时, 按仓储受影响行数返回是否删除成功")
  void shouldReportRemoved_whenDeletingCatalogRow() {
    when(catalogMapper.deleteByKey("shard-9")).thenReturn(1);
    assertThat(service.delete("shard-9")).isTrue();
  }
}
