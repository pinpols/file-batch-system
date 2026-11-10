package io.github.pinpols.batch.worker.imports.runtime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.worker.imports.runtime.ImportIngressScanRepository.Lease;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("导入扫描大文件租约续期")
class ImportScanLeaseKeepaliveTest {
  @Mock
  private ImportIngressScanRepository repository;

  @Test
  @DisplayName("到达续期间隔才续租；失去租约时中断对象校验")
  void leaseKeepalive_shouldRenewOnlyWhenDueAndStopAfterLeaseLoss() {
    Lease lease = Lease.builder()
        .storageId("storage")
        .storageFence(1)
        .scopeId("scope")
        .owner(UUID.randomUUID())
        .fence(1)
        .phase("APPLY")
        .cycle(1)
        .build();
    AtomicLong nanos = new AtomicLong();
    ImportScanLeaseKeepalive keepalive =
        new ImportScanLeaseKeepalive(repository, lease, nanos::get);
    nanos.set(19_000_000_000L);
    keepalive.renewIfDue();
    verify(repository, never()).fenced(eq(lease), any());

    when(repository.fenced(eq(lease), any())).thenReturn(true, false);
    nanos.set(20_000_000_000L);
    keepalive.renewIfDue();
    nanos.set(40_000_000_000L);
    assertThatThrownBy(keepalive::renewIfDue)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("租约已失效");
    verify(repository, times(2)).fenced(eq(lease), any());
  }
}
