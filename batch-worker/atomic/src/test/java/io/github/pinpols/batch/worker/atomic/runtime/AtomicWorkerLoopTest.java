package io.github.pinpols.batch.worker.atomic.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.dto.WorkerTaskCapabilityDto;
import io.github.pinpols.batch.common.spi.task.BatchTaskExecutorRegistry;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.worker.atomic.config.AtomicWorkerConfiguration;
import io.github.pinpols.batch.worker.core.config.WorkerConcurrencyProperties;
import io.github.pinpols.batch.worker.core.config.WorkerIdentityProperties;
import io.github.pinpols.batch.worker.core.config.WorkerRegistryStartupProperties;
import io.github.pinpols.batch.worker.core.support.HeartbeatService;
import io.github.pinpols.batch.worker.core.support.WorkerLifecycleManager;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Atomic Worker 注册能力快照")
class AtomicWorkerLoopTest {

  @Test
  @DisplayName("从本地执行器注册表读取能力摘要")
  void shouldExposeRegisteredExecutorCapabilities() {
    BatchTaskExecutorRegistry taskExecutorRegistry = mock(BatchTaskExecutorRegistry.class);
    List<WorkerTaskCapabilityDto> capabilities =
        List.of(new WorkerTaskCapabilityDto("checksum", List.of("DISK"), true, false, 1));
    when(taskExecutorRegistry.capabilitySnapshot()).thenReturn(capabilities);

    AtomicWorkerLoop loop = new AtomicWorkerLoop(
        mock(WorkerLifecycleManager.class),
        mock(HeartbeatService.class),
        mock(BatchDateTimeSupport.class),
        new AtomicWorkerConfiguration(
            "atomic-1", "TASK", "tenant-a", 15000L, "topic", "group", List.of()),
        new WorkerIdentityProperties(),
        new WorkerRegistryStartupProperties(),
        new WorkerConcurrencyProperties(),
        taskExecutorRegistry);

    assertThat(loop.taskCapabilities()).containsExactlyElementsOf(capabilities);
  }
}
