package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.support.StaticApplicationContext;

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
    service =
        new DispatchChannelHealthService(
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
  void probeConfiguredChannelsSkipsAfterContextClosed() {
    service.stopOnContextClosed(new ContextClosedEvent(new StaticApplicationContext()));

    service.probeConfiguredChannels();

    verify(repository, never()).findEnabledProbeChannels(anyList(), anyInt());
  }
}
