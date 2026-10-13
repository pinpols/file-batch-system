package io.github.pinpols.batch.worker.dispatchs.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("派送通道 Mapper 状态默认方法:复制输入并固定健康状态")
class DispatchChannelHealthMapperStatusTest {

  @Test
  @DisplayName("成功与失败写入不会修改调用方参数")
  void shouldCopyParamsAndAddHealthStatuses() {
    DispatchChannelHealthMapper mapper =
        mock(DispatchChannelHealthMapper.class, CALLS_REAL_METHODS);
    doReturn(1).when(mapper).upsertSuccessWithStatus(any());
    doReturn(1).when(mapper).upsertFailureAndBumpWithStatuses(any());
    Map<String, Object> input = new LinkedHashMap<>(Map.of("tenantId", "tenant-a"));
    ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.captor();

    assertThat(mapper.upsertSuccess(input)).isEqualTo(1);
    assertThat(mapper.upsertFailureAndBump(input)).isEqualTo(1);
    verify(mapper).upsertSuccessWithStatus(captor.capture());
    verify(mapper, times(1)).upsertFailureAndBumpWithStatuses(captor.capture());

    assertThat(input).containsOnlyKeys("tenantId");
    assertThat(captor.getAllValues().get(0)).containsEntry("healthyStatus", "HEALTHY");
    assertThat(captor.getAllValues().get(1))
        .containsEntry("degradedStatus", "DEGRADED")
        .containsEntry("unhealthyStatus", "UNHEALTHY");
  }
}
