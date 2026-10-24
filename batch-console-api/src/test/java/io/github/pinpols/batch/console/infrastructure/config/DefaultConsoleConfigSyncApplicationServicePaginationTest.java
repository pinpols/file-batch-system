package io.github.pinpols.batch.console.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.console.application.config.ConsoleTenantConfigCopyService;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.mapper.ConfigSyncLogMapper;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(MockitoExtension.class)
@DisplayName("配置同步日志分页服务: 总数与切片结果")
class DefaultConsoleConfigSyncApplicationServicePaginationTest {

  @Mock private ConsoleTenantGuard tenantGuard;
  @Mock private ConsoleTenantConfigCopyService copyService;
  @Mock private ConfigSyncImportExecutor importExecutor;
  @Mock private ConsoleRequestMetadataResolver metadataResolver;
  @Mock private ConfigSyncLogMapper mapper;
  @Mock private PlatformTransactionManager transactionManager;
  @InjectMocks private DefaultConsoleConfigSyncApplicationService service;

  @Test
  @DisplayName("按页码计算偏移并映射审计日志")
  void logs_returns_requested_slice_and_total() {
    when(tenantGuard.resolveTenant("ta")).thenReturn("ta");
    when(mapper.countByTenant("ta")).thenReturn(42L);
    when(mapper.selectByTenant("ta", 30L, 15))
        .thenReturn(List.of(Map.of(
            "id", 9L,
            "tenantId", "ta",
            "syncDirection", "IMPORT",
            "syncStatus", "SUCCESS")));

    var response = service.logs("ta", 3, 15);

    assertThat(response.total()).isEqualTo(42L);
    assertThat(response.pageNo()).isEqualTo(3);
    assertThat(response.pageSize()).isEqualTo(15);
    assertThat(response.items().getFirst().id()).isEqualTo(9L);
    verify(mapper).selectByTenant("ta", 30L, 15);
  }
}
