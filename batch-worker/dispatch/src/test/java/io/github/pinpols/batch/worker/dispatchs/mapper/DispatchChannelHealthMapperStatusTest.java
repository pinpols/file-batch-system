package io.github.pinpols.batch.worker.dispatchs.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.github.pinpols.batch.worker.dispatchs.param.DispatchChannelHealthFailureParam;
import io.github.pinpols.batch.worker.dispatchs.param.DispatchChannelHealthSuccessParam;
import java.sql.Timestamp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("派送通道 Mapper 状态默认方法：传递具名参数并固定健康状态")
class DispatchChannelHealthMapperStatusTest {

  @Test
  @DisplayName("成功与失败写入保留参数对象并绑定各自的固定状态")
  void shouldPassTypedParamsAndFixHealthStatuses() {
    DispatchChannelHealthMapper mapper =
        mock(DispatchChannelHealthMapper.class, CALLS_REAL_METHODS);
    doReturn(1).when(mapper).upsertSuccessWithStatus(any(), any());
    doReturn(1).when(mapper).upsertFailureAndBumpWithStatuses(any(), any(), any());
    Timestamp now = Timestamp.valueOf("2026-10-27 00:00:00");
    DispatchChannelHealthSuccessParam success = new DispatchChannelHealthSuccessParam(
        "tenant-a", "channel-a", "API", now, now, "ok", "success-evidence");
    DispatchChannelHealthFailureParam failure = new DispatchChannelHealthFailureParam(
        "tenant-a", "channel-a", "API", now, now, 5, "timeout", "failure-evidence");

    assertThat(mapper.upsertSuccess(success)).isEqualTo(1);
    assertThat(mapper.upsertFailureAndBump(failure)).isEqualTo(1);

    verify(mapper).upsertSuccessWithStatus(same(success), eq("HEALTHY"));
    verify(mapper).upsertFailureAndBumpWithStatuses(same(failure), eq("DEGRADED"), eq("UNHEALTHY"));
  }
}
