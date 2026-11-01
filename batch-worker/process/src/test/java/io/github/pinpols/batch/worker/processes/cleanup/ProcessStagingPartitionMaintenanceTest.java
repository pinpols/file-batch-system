package io.github.pinpols.batch.worker.processes.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.worker.processes.mapper.business.ProcessStagingMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

/** {@link ProcessStagingOrphanCleaner#maintainPartitions()} 分区维护逻辑单测。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("处理暂存区分区维护:只预建分区，删除需单独完成生命周期验证")
class ProcessStagingPartitionMaintenanceTest {

  @Mock
  private ProcessStagingMapper mapper;

  @Mock
  private ObjectProvider<MeterRegistry> meterRegistryProvider;

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

    // 执行
    cleaner.maintainPartitions();

    // 断言:今天 + 未来 2 天 = 3 个分区
    verify(mapper, times(3)).createDailyPartition(anyString(), anyString(), anyString());
    verify(mapper, never()).deleteGenerationRows(ArgumentMatchers.any(), anyInt());
  }

  @Test
  @DisplayName("过期分区不因 deletion-enabled 被直接 DROP")
  void shouldNeverDropPartitionsFromCalendarAgeAlone() {
    properties.setPreCreateDays(0);
    properties.setDeletionEnabled(true);

    cleaner.maintainPartitions();

    verify(mapper).createDailyPartition(anyString(), anyString(), anyString());
    verify(mapper, never()).deleteGenerationRows(ArgumentMatchers.any(), anyInt());
  }

  @Test
  @DisplayName("默认不按年龄删除 staging 行")
  void shouldKeepRowsUntilDeletionIsEnabled() {
    assertThat(properties.isDeletionEnabled()).isFalse();
    assertThat(cleaner.cleanOnce()).isZero();
    verifyNoInteractions(mapper);
  }

  @Test
  @DisplayName("日分区名 + UTC 整天边界格式正确")
  void shouldCreatePartitionWithUtcDayBounds() {
    // 准备
    properties.setPreCreateDays(0);
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
