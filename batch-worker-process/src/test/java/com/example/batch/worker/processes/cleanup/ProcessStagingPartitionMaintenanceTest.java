package com.example.batch.worker.processes.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.worker.processes.mapper.business.ProcessStagingMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

/** {@link ProcessStagingOrphanCleaner#maintainPartitions()} 分区维护逻辑单测。 */
@ExtendWith(MockitoExtension.class)
class ProcessStagingPartitionMaintenanceTest {

  @Mock private ProcessStagingMapper mapper;
  @Mock private ObjectProvider<MeterRegistry> meterRegistryProvider;

  private ProcessStagingCleanupProperties properties;
  private ProcessStagingOrphanCleaner cleaner;

  @BeforeEach
  void setUp() {
    when(meterRegistryProvider.getIfAvailable()).thenReturn(null);
    properties = new ProcessStagingCleanupProperties();
    cleaner = new ProcessStagingOrphanCleaner(mapper, properties, meterRegistryProvider);
  }

  @Test
  @DisplayName("预建今天 + preCreateDays 天的日分区")
  void shouldPreCreateTodayAndFutureDays() {
    // 准备
    properties.setPreCreateDays(2);
    properties.setRetentionDays(3);
    when(mapper.listExpiredDailyPartitions(anyString())).thenReturn(List.of());

    // 执行
    cleaner.maintainPartitions();

    // 断言: today + 2 future = 3 个分区
    verify(mapper, times(3)).createDailyPartition(anyString(), anyString(), anyString());
    verify(mapper).listExpiredDailyPartitions(anyString());
  }

  @Test
  @DisplayName("DROP 所有过期日分区")
  void shouldDropExpiredPartitions() {
    // 准备
    properties.setPreCreateDays(0);
    properties.setRetentionDays(3);
    when(mapper.listExpiredDailyPartitions(anyString()))
        .thenReturn(List.of("process_staging_p20260101", "process_staging_p20260102"));

    // 执行
    cleaner.maintainPartitions();

    // 断言
    verify(mapper).dropPartition("process_staging_p20260101");
    verify(mapper).dropPartition("process_staging_p20260102");
  }

  @Test
  @DisplayName("retentionDays<=0 关闭自动 DROP")
  void shouldSkipDropWhenRetentionDisabled() {
    // 准备
    properties.setPreCreateDays(0);
    properties.setRetentionDays(0);

    // 执行
    cleaner.maintainPartitions();

    // 断言: 不查、不 DROP 任何分区
    verify(mapper, never()).listExpiredDailyPartitions(anyString());
    verify(mapper, never()).dropPartition(anyString());
  }

  @Test
  @DisplayName("日分区名 + UTC 整天边界格式正确")
  void shouldCreatePartitionWithUtcDayBounds() {
    // 准备
    properties.setPreCreateDays(0);
    properties.setRetentionDays(0);
    ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> from = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);

    // 执行
    cleaner.maintainPartitions();

    // 断言
    verify(mapper).createDailyPartition(name.capture(), from.capture(), to.capture());
    assertThat(name.getValue()).matches("process_staging_p\\d{8}");
    assertThat(from.getValue()).matches("\\d{4}-\\d{2}-\\d{2} 00:00:00\\+00");
    assertThat(to.getValue()).matches("\\d{4}-\\d{2}-\\d{2} 00:00:00\\+00");
  }
}
