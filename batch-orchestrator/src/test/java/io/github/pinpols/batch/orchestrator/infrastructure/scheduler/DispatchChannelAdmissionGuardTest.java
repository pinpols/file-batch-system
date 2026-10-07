package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceCheck;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceSchedulingRequest;
import io.github.pinpols.batch.orchestrator.mapper.DownstreamAdmissionMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("投递通道准入校验 - 校验调度准入按下游通道健康状态延迟或放行")
class DispatchChannelAdmissionGuardTest {

  private final DownstreamAdmissionMapper mapper = mock(DownstreamAdmissionMapper.class);
  private final DispatchChannelAdmissionGuard guard = new DispatchChannelAdmissionGuard(mapper);

  @Test
  @DisplayName("投递任务命中的下游通道被阻断时判定不予放行并给出通道不可用原因")
  void shouldDeferAdmission_whenDispatchChannelIsBlocked() {
    ResourceSchedulingRequest request = dispatchRequest("SFTP_SETTLEMENT");
    when(mapper.isAnyDispatchChannelBlocked("t1", List.of("SFTP_SETTLEMENT"))).thenReturn(true);

    ResourceCheck result = guard.check(request);

    assertThat(result.allowed()).isFalse();
    assertThat(result.reasonCode()).isEqualTo("DOWNSTREAM_CHANNEL_UNAVAILABLE");
  }

  @Test
  @DisplayName("组合投递通道中任一通道被阻断时整体判定不予放行")
  void shouldDeferWholeAdmission_whenAnyBundleChannelIsBlocked() {
    ResourceSchedulingRequest request = dispatchRequest(null);
    request.setDownstreamChannelCodes(List.of("SFTP_SETTLEMENT", "OSS_ARCHIVE"));
    when(mapper.isAnyDispatchChannelBlocked("t1", List.of("SFTP_SETTLEMENT", "OSS_ARCHIVE")))
        .thenReturn(true);

    ResourceCheck result = guard.check(request);

    assertThat(result.allowed()).isFalse();
    assertThat(result.reasonCode()).isEqualTo("DOWNSTREAM_CHANNEL_UNAVAILABLE");
  }

  @Test
  @DisplayName("非投递类任务不读取通道健康状态而直接判定放行")
  void shouldAllowAdmission_whenTaskIsNotDispatch() {
    ResourceSchedulingRequest request = dispatchRequest("SFTP_SETTLEMENT");
    request.setWorkerType("PROCESS");

    assertThat(guard.check(request).allowed()).isTrue();
  }

  private static ResourceSchedulingRequest dispatchRequest(String channelCode) {
    ResourceSchedulingRequest request = new ResourceSchedulingRequest();
    request.setTenantId("t1");
    request.setWorkerType("DISPATCH");
    request.setDownstreamChannelCode(channelCode);
    return request;
  }
}
