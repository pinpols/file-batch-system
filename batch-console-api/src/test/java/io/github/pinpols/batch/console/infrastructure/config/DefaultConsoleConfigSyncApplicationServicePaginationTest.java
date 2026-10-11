package io.github.pinpols.batch.console.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.console.application.config.ConsoleTenantConfigCopyService;
import io.github.pinpols.batch.console.application.config.ConsoleTenantConfigInitApplicationService;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.mapper.ConfigSyncLogMapper;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

@DisplayName("配置同步日志分页服务: 总数与切片结果")
class DefaultConsoleConfigSyncApplicationServicePaginationTest {

  private final ConsoleTenantGuard tenantGuard = mock(ConsoleTenantGuard.class);
  private final ConsoleTenantConfigCopyService copyService =
      mock(ConsoleTenantConfigCopyService.class);
  private final ConsoleTenantConfigInitApplicationService initService =
      mock(ConsoleTenantConfigInitApplicationService.class);
  private final ConsoleRequestMetadataResolver metadataResolver =
      mock(ConsoleRequestMetadataResolver.class);
  private final ConfigSyncLogMapper mapper = mock(ConfigSyncLogMapper.class);
  private final PlatformTransactionManager transactionManager =
      mock(PlatformTransactionManager.class);
  private final DefaultConsoleConfigSyncApplicationService service =
      new DefaultConsoleConfigSyncApplicationService(
          tenantGuard, copyService, initService, metadataResolver, mapper, transactionManager);

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

    assertEquals(42L, response.total());
    assertEquals(3, response.pageNo());
    assertEquals(15, response.pageSize());
    assertEquals(9L, response.items().getFirst().id());
    verify(mapper).selectByTenant("ta", 30L, 15);
  }
}
