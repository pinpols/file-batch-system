package io.github.pinpols.batch.orchestrator.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.domain.query.PersistentWaitReadFilter;
import io.github.pinpols.batch.orchestrator.domain.query.PersistentWaitReadQuery;
import io.github.pinpols.batch.orchestrator.domain.query.PersistentWaitReadRow;
import io.github.pinpols.batch.orchestrator.mapper.PersistentWaitReadMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("持久等待查询服务")
class PersistentWaitReadServiceTest {

  @Mock
  private PersistentWaitReadMapper mapper;

  @InjectMocks
  private PersistentWaitReadService service;

  @Test
  @DisplayName("租户查询工厂拒绝空租户")
  void shouldRejectBlankTenantInQueryFactory() {
    assertThatThrownBy(() -> PersistentWaitReadQuery.ofTenant(" "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("按租户查询并限制分页大小")
  void shouldScopeQueryToTenantAndClampPageSize_whenListingWaits() {
    PersistentWaitReadRow row = new PersistentWaitReadRow();
    row.setTenantId("tenant-a");
    PersistentWaitReadFilter filter =
        new PersistentWaitReadFilter("tenant-a", null, null, null, null, null);
    when(mapper.selectPage(filter, 100, 0L)).thenReturn(List.of(row));
    when(mapper.count(filter)).thenReturn(1L);

    PersistentWaitReadService.PersistentWaitPage page =
        service.list(PersistentWaitReadQuery.ofTenantAndWaitType("tenant-a", " ", 0, 500));

    assertThat(page.pageNo()).isEqualTo(1);
    assertThat(page.pageSize()).isEqualTo(100);
    assertThat(page.total()).isEqualTo(1L);
    assertThat(page.items()).containsExactly(row);
    verify(mapper).setTenantContext("tenant-a");
    verify(mapper).selectPage(filter, 100, 0L);
  }

  @Test
  @DisplayName("分页参数省略时保留原接口默认值")
  void shouldApplyOriginalDefaults_whenPageParametersAreMissing() {
    PersistentWaitReadFilter filter =
        new PersistentWaitReadFilter("tenant-a", null, null, null, null, null);
    when(mapper.selectPage(filter, 15, 0L)).thenReturn(List.of());
    when(mapper.count(filter)).thenReturn(0L);

    PersistentWaitReadService.PersistentWaitPage page =
        service.list(PersistentWaitReadQuery.ofTenant("tenant-a"));

    assertThat(page.pageNo()).isEqualTo(1);
    assertThat(page.pageSize()).isEqualTo(15);
    verify(mapper).setTenantContext("tenant-a");
  }
}
