package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceCheck;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceSchedulingRequest;
import io.github.pinpols.batch.orchestrator.mapper.DownstreamAdmissionMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class DispatchChannelAdmissionGuardTest {

  private final DownstreamAdmissionMapper mapper = mock(DownstreamAdmissionMapper.class);
  private final DispatchChannelAdmissionGuard guard = new DispatchChannelAdmissionGuard(mapper);

  @Test
  void unhealthyDispatchChannelDefersBeforeKafkaDispatch() {
    ResourceSchedulingRequest request = dispatchRequest("SFTP_SETTLEMENT");
    when(mapper.isAnyDispatchChannelBlocked("t1", List.of("SFTP_SETTLEMENT"))).thenReturn(true);

    ResourceCheck result = guard.check(request);

    assertThat(result.allowed()).isFalse();
    assertThat(result.reasonCode()).isEqualTo("DOWNSTREAM_CHANNEL_UNAVAILABLE");
  }

  @Test
  void anyUnhealthyBundleChannelDefersTheWholeAdmission() {
    ResourceSchedulingRequest request = dispatchRequest(null);
    request.setDownstreamChannelCodes(List.of("SFTP_SETTLEMENT", "OSS_ARCHIVE"));
    when(mapper.isAnyDispatchChannelBlocked("t1", List.of("SFTP_SETTLEMENT", "OSS_ARCHIVE")))
        .thenReturn(true);

    ResourceCheck result = guard.check(request);

    assertThat(result.allowed()).isFalse();
    assertThat(result.reasonCode()).isEqualTo("DOWNSTREAM_CHANNEL_UNAVAILABLE");
  }

  @Test
  void nonDispatchTaskDoesNotReadChannelHealth() {
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
