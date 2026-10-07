package io.github.pinpols.batch.common.storage;

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link MeteredObjectStore} 单测:成功/失败都打点(operation+outcome 标签),且不改语义透传委托。 */
@DisplayName("计量对象存储:验证成功与失败调用的指标打点标签、原始异常透传以及能力探测不打点")
class MeteredObjectStoreTest {

  private static final String TIMER = "batch.objectstore.op";
  private static final String BUCKET = "b";
  private static final String KEY = "k";

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final BatchObjectStore delegate = mock(BatchObjectStore.class);
  private final MeteredObjectStore metered = new MeteredObjectStore(delegate, registry);

  @Test
  @DisplayName("读取成功时按操作与结果标签累计一次耗时计数,并原样返回底层数据流")
  void shouldRecordSuccessTimerForGet() {
    byte[] payload = "hi".getBytes(StandardCharsets.UTF_8);
    when(delegate.get(BUCKET, KEY)).thenReturn(new ByteArrayInputStream(payload));

    InputStream in = metered.get(BUCKET, KEY);

    assertThat(in).isNotNull();
    verify(delegate).get(BUCKET, KEY);
    Timer t =
        registry.find(TIMER).tag("operation", "get").tag("outcome", "success").timer();
    assertThat(t).isNotNull();
    assertThat(t.count()).isEqualTo(1);
  }

  @Test
  @DisplayName("写入成功时按操作与结果标签累计一次耗时计数,并透传到底层")
  void shouldRecordSuccessTimerForPut() {
    byte[] payload = "x".getBytes(StandardCharsets.UTF_8);

    metered.put(BUCKET, KEY, new ByteArrayInputStream(payload), payload.length, "text/plain");

    verify(delegate).put(anyString(), anyString(), any(), anyLong(), anyString());
    Timer t =
        registry.find(TIMER).tag("operation", "put").tag("outcome", "success").timer();
    assertThat(t).isNotNull();
    assertThat(t.count()).isEqualTo(1);
  }

  @Test
  @DisplayName("删除失败时记录失败结果标签,并把原始异常向上抛出")
  void shouldRecordErrorTimerAndPropagateException() {
    doThrow(new RuntimeException("boom")).when(delegate).delete(BUCKET, KEY);

    assertThatThrownBy(() -> metered.delete(BUCKET, KEY))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("boom");

    Timer t =
        registry.find(TIMER).tag("operation", "delete").tag("outcome", "error").timer();
    assertThat(t).isNotNull();
    assertThat(t.count()).isEqualTo(1);
  }

  @Test
  @DisplayName("能力探测直接返回底层配置,且不产生任何耗时指标")
  void shouldDelegateCapabilityFlagsWithoutTiming() {
    when(delegate.supportsRangeRead()).thenReturn(true);
    when(delegate.supportsPresignPut()).thenReturn(false);

    assertThat(metered.supportsRangeRead()).isTrue();
    assertThat(metered.supportsPresignPut()).isFalse();
    // 能力探测不打点。
    assertThat(registry.find(TIMER).timers()).isEmpty();
  }
}
