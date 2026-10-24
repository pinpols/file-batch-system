package io.github.pinpols.batch.worker.processes.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PROCESS staging cleanup 在开启业务事务前绑定租户路由")
class ProcessStagingBusinessCleanupServiceTest {

  private final ProcessStagingBusinessCleanupTransaction transaction =
      mock(ProcessStagingBusinessCleanupTransaction.class);
  private final ProcessStagingBusinessCleanupService service =
      new ProcessStagingBusinessCleanupService(transaction);

  @AfterEach
  void clearTenantContext() {
    RlsTenantContextHolder.clear();
  }

  @Test
  void shouldBindTenantBeforeEnteringTransactionalDelegate() {
    when(transaction.listExpired("tenant_a", 24, 50, 0)).thenAnswer(invocation -> {
      assertThat(RlsTenantContextHolder.get()).isEqualTo("tenant_a");
      return List.of();
    });

    assertThat(service.listExpired("tenant_a", 24, 50, 0)).isEmpty();
    assertThat(RlsTenantContextHolder.get()).isNull();
  }
}
