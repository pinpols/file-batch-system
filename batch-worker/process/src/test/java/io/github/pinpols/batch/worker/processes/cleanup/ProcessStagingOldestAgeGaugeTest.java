package io.github.pinpols.batch.worker.processes.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.worker.processes.mapper.business.ProcessStagingMapper;
import io.github.pinpols.batch.worker.processes.mapper.platform.ProcessInvocationFenceMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

@DisplayName("PROCESS 暂存最早行年龄指标")
class ProcessStagingOldestAgeGaugeTest {
  @Test
  @DisplayName("按租户取最早暂存时间时每个租户最多扫描 500 行，并取所有租户中最早的一条")
  @SuppressWarnings("unchecked")
  void shouldScanOldestStagedRowsWithLimitPerTenant() {
    ProcessStagingMapper mapper = mock(ProcessStagingMapper.class);
    ProcessInvocationFenceMapper platformMapper = mock(ProcessInvocationFenceMapper.class);
    ProcessStagingBusinessCleanupService business =
        mock(ProcessStagingBusinessCleanupService.class);
    ProcessStagingGenerationCleanupFence fence = mock(ProcessStagingGenerationCleanupFence.class);
    ObjectProvider<MeterRegistry> registry = mock(ObjectProvider.class);
    ProcessStagingCleanupProperties properties = new ProcessStagingCleanupProperties();
    Instant hourAgo = Instant.now().minusSeconds(3600);
    when(platformMapper.listCleanupTenants()).thenReturn(List.of("t1", "t2"));
    when(business.selectOldestStagedAt("t1", 500)).thenReturn(hourAgo.plusSeconds(600));
    when(business.selectOldestStagedAt("t2", 500)).thenReturn(hourAgo);
    ProcessStagingOrphanCleaner cleaner = new ProcessStagingOrphanCleaner(
        mapper, properties, registry, platformMapper, business, fence);

    cleaner.scheduledClean();

    verify(business).selectOldestStagedAt("t1", 500);
    verify(business).selectOldestStagedAt("t2", 500);
    assertThat(cleaner.oldestAgeSecondsForGauge()).isGreaterThanOrEqualTo(3600d);
  }
}
