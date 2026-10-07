package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.config.S3StorageProperties;
import io.github.pinpols.batch.common.storage.BatchObjectStore;
import io.github.pinpols.batch.worker.dispatchs.config.DispatchChannelHealthProperties;
import io.github.pinpols.batch.worker.dispatchs.config.DispatchCircuitBreakerProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.support.StaticApplicationContext;

@DisplayName("渠道健康服务:上下文关闭后停止探测,以及失败退避直接采用落库返回的失败计数")
class DispatchChannelHealthServiceTest {

  private DispatchChannelHealthRepository repository;
  private DispatchChannelHealthService service;

  @BeforeEach
  void setUp() {
    repository = mock(DispatchChannelHealthRepository.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<BatchObjectStore> objectStoreProvider = mock(ObjectProvider.class);
    when(objectStoreProvider.getIfAvailable()).thenReturn(null);
    DispatchChannelHealthProperties properties = new DispatchChannelHealthProperties();
    DispatchCircuitBreakerProperties circuitBreakerProperties =
        new DispatchCircuitBreakerProperties();
    service = new DispatchChannelHealthService(
        repository,
        properties,
        circuitBreakerProperties,
        new S3StorageProperties(),
        new BatchSecurityProperties(),
        new ObjectMapper(),
        new SimpleMeterRegistry(),
        objectStoreProvider);
    service.init();
  }

  @Test
  @DisplayName("上下文关闭后再触发探测时,不再查询任何启用探测的渠道")
  void shouldSkipProbeChannels_whenContextClosed() {
    service.stopOnContextClosed(new ContextClosedEvent(new StaticApplicationContext()));

    service.probeConfiguredChannels();

    verify(repository, never()).findEnabledProbeChannels(anyList(), anyInt());
  }

  @Test
  @DisplayName("记录投递失败时直接用落库返回的失败次数重算退避,不再回读健康快照")
  void shouldUseReturnedFailureCount_whenRecordingDispatchFailure() {
    when(repository.upsertFailureAndBump(any())).thenReturn(4);
    Map<String, Object> channel =
        Map.of("tenant_id", "tenant-a", "channel_code", "channel-a", "channel_type", "API");

    service.recordDispatchOutcome(channel, false, "timeout", null);

    verify(repository).recalcBackoff(any(), eq(4));
    verify(repository, never()).findHealth("tenant-a", "channel-a");
  }
}
