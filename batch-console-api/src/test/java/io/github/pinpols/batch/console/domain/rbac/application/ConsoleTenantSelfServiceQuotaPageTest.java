package io.github.pinpols.batch.console.domain.rbac.application;

import static org.mockito.Mockito.verify;

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.application.config.ConsoleQuotaPolicyApplicationService;
import io.github.pinpols.batch.console.application.observability.ConsoleSystemParameterService;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("租户自助应用服务：配额查询分页")
class ConsoleTenantSelfServiceQuotaPageTest {
  @Mock
  private ConsoleQuotaPolicyApplicationService quotaService;

  @Mock
  private ConsoleSystemParameterService parameterService;

  @Mock
  private ConsoleTenantGuard tenantGuard;

  @Mock
  private BatchDateTimeSupport timeSupport;

  @Mock
  private ConsoleRequestMetadataResolver metadataResolver;

  @Test
  @DisplayName("配额查询只取第一页且每页 100 条")
  void shouldListFirstQuotaPageWithHundredRows() {
    ConsoleTenantSelfServiceApplicationService service =
        new ConsoleTenantSelfServiceApplicationService(
            quotaService, parameterService, tenantGuard, timeSupport, metadataResolver);

    service.quota("tenant-a");

    verify(quotaService).listAll("tenant-a", 1, 100);
  }
}
