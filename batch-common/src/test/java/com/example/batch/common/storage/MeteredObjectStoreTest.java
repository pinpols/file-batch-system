package com.example.batch.common.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** {@link MeteredObjectStore} 单测:成功/失败都打点(operation+outcome 标签),且不改语义透传委托。 */
class MeteredObjectStoreTest {

  private static final String TIMER = "batch.objectstore.op";
  private static final String BUCKET = "b";
  private static final String KEY = "k";

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final BatchObjectStore delegate = mock(BatchObjectStore.class);
  private final MeteredObjectStore metered = new MeteredObjectStore(delegate, registry);

  @Test
  void shouldRecordSuccessTimerForGet() {
    byte[] payload = "hi".getBytes(StandardCharsets.UTF_8);
    when(delegate.get(BUCKET, KEY)).thenReturn(new ByteArrayInputStream(payload));

    InputStream in = metered.get(BUCKET, KEY);

    assertThat(in).isNotNull();
    verify(delegate).get(BUCKET, KEY);
    Timer t = registry.find(TIMER).tag("operation", "get").tag("outcome", "success").timer();
    assertThat(t).isNotNull();
    assertThat(t.count()).isEqualTo(1);
  }

  @Test
  void shouldRecordSuccessTimerForPut() {
    byte[] payload = "x".getBytes(StandardCharsets.UTF_8);

    metered.put(BUCKET, KEY, new ByteArrayInputStream(payload), payload.length, "text/plain");

    verify(delegate).put(anyString(), anyString(), any(), anyLong(), anyString());
    Timer t = registry.find(TIMER).tag("operation", "put").tag("outcome", "success").timer();
    assertThat(t).isNotNull();
    assertThat(t.count()).isEqualTo(1);
  }

  @Test
  void shouldRecordErrorTimerAndPropagateException() {
    doThrow(new RuntimeException("boom")).when(delegate).delete(BUCKET, KEY);

    assertThatThrownBy(() -> metered.delete(BUCKET, KEY))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("boom");

    Timer t = registry.find(TIMER).tag("operation", "delete").tag("outcome", "error").timer();
    assertThat(t).isNotNull();
    assertThat(t.count()).isEqualTo(1);
  }

  @Test
  void shouldDelegateCapabilityFlagsWithoutTiming() {
    when(delegate.supportsRangeRead()).thenReturn(true);
    when(delegate.supportsPresignPut()).thenReturn(false);

    assertThat(metered.supportsRangeRead()).isTrue();
    assertThat(metered.supportsPresignPut()).isFalse();
    // 能力探测不打点。
    assertThat(registry.find(TIMER).timers()).isEmpty();
  }
}
