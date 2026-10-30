package io.github.pinpols.batch.worker.processes.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("PROCESS staging cleanup 在开启业务事务前绑定租户路由")
@ExtendWith(MockitoExtension.class)
class ProcessStagingBusinessCleanupServiceTest {

  @Mock
  private ProcessStagingBusinessCleanupTransaction transaction;

  private ProcessStagingBusinessCleanupService service;

  @AfterEach
  void clearTenantContext() {
    RlsTenantContextHolder.clear();
  }

  @DisplayName("进入事务代理前绑定租户上下文")
  @Test
  void shouldBindTenantBeforeEnteringTransactionalDelegate() {
    when(transaction.listExpired("tenant_a", 24, 50, 0)).thenAnswer(invocation -> {
      assertThat(RlsTenantContextHolder.get()).isEqualTo("tenant_a");
      return List.of();
    });

    assertThat(service.listExpired("tenant_a", 24, 50, 0)).isEmpty();
    assertThat(RlsTenantContextHolder.get()).isNull();
  }

  @BeforeEach
  void initializeSubject() {
    service = new ProcessStagingBusinessCleanupService(transaction);
  }

  @Test
  @DisplayName("游标扫描在进入业务事务前绑定租户并传递完整游标")
  void shouldBindTenantAndPassCursorForExpiredScan() {
    Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
    String generationId = "10000000-0000-0000-0000-000000000001";
    when(transaction.listExpiredAfter("tenant_a", 24, 50, createdAt, generationId))
        .thenAnswer(invocation -> {
          assertThat(RlsTenantContextHolder.get()).isEqualTo("tenant_a");
          return List.of();
        });

    assertThat(service.listExpiredAfter("tenant_a", 24, 50, createdAt, generationId))
        .isEmpty();
    assertThat(RlsTenantContextHolder.get()).isNull();
  }
}
