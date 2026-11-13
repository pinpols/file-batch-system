package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.config.S3StorageProperties;
import io.github.pinpols.batch.common.storage.BatchObjectStore;
import io.github.pinpols.batch.worker.dispatchs.config.DispatchChannelHealthProperties;
import io.github.pinpols.batch.worker.dispatchs.config.DispatchCircuitBreakerProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

@DisplayName("渠道健康服务:半开探测通行证持有时长不低于 1 秒")
class DispatchChannelHalfOpenHoldTest {

  @Test
  @DisplayName("配置的持有时长小于 1 秒时按 1 秒占用半开通行证")
  void shouldFloorHalfOpenHoldToOneSecond() {
    DispatchChannelHealthRepository repository = mock(DispatchChannelHealthRepository.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<BatchObjectStore> objectStoreProvider = mock(ObjectProvider.class);
    DispatchChannelHealthProperties properties = new DispatchChannelHealthProperties();
    properties.setHalfOpenHoldMillis(10L);
    DispatchChannelHealthService service = new DispatchChannelHealthService(
        repository,
        properties,
        new DispatchCircuitBreakerProperties(),
        new S3StorageProperties(),
        new BatchSecurityProperties(),
        new ObjectMapper(),
        new SimpleMeterRegistry(),
        objectStoreProvider);
    Instant pastProbeAt = Instant.now().minus(Duration.ofMinutes(1));
    DispatchChannelHealthSnapshot unhealthy = new DispatchChannelHealthSnapshot(
        "tenant-a", "ch-a", "API", "UNHEALTHY", 3, null, null, null, pastProbeAt, null, null);
    when(repository.findHealth("tenant-a", "ch-a")).thenReturn(unhealthy);
    when(repository.tryClaimHalfOpenProbe(eq("tenant-a"), eq("ch-a"), any(), any()))
        .thenReturn(true);

    boolean allowed =
        service.allowDispatch(Map.of("tenant_id", "tenant-a", "channel_code", "ch-a"));

    assertThat(allowed).isTrue();
    ArgumentCaptor<Instant> now = ArgumentCaptor.forClass(Instant.class);
    ArgumentCaptor<Instant> hold = ArgumentCaptor.forClass(Instant.class);
    verify(repository)
        .tryClaimHalfOpenProbe(eq("tenant-a"), eq("ch-a"), now.capture(), hold.capture());
    assertThat(Duration.between(now.getValue(), hold.getValue())).isEqualTo(Duration.ofSeconds(1));
  }
}
