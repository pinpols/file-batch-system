package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.application.scheduler.DownstreamAdmissionGuard;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceCheck;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceSchedulingRequest;
import io.github.pinpols.batch.orchestrator.mapper.DownstreamAdmissionMapper;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 使用 dispatch worker 写入的平台健康快照，在生成 Kafka 消息前阻断已知故障渠道。 */
@Component
@RequiredArgsConstructor
public class DispatchChannelAdmissionGuard implements DownstreamAdmissionGuard {

  private final DownstreamAdmissionMapper downstreamAdmissionMapper;

  @Override
  public ResourceCheck check(ResourceSchedulingRequest request) {
    if (EmptyChecks.isNull(request)
        || !"DISPATCH".equalsIgnoreCase(request.getWorkerType())
        || !Texts.hasText(request.getTenantId())) {
      return ResourceCheck.allow();
    }
    List<String> channelCodes = resolveChannelCodes(request);
    if (EmptyChecks.isEmpty(channelCodes)) {
      return ResourceCheck.allow();
    }
    if (downstreamAdmissionMapper.isAnyDispatchChannelBlocked(
        request.getTenantId(), channelCodes)) {
      return ResourceCheck.waitForCapacity(
          "DOWNSTREAM_CHANNEL_UNAVAILABLE",
          "dispatch channel is unhealthy and waiting for a successful health probe");
    }
    return ResourceCheck.allow();
  }

  private List<String> resolveChannelCodes(ResourceSchedulingRequest request) {
    Set<String> channelCodes = new LinkedHashSet<>();
    if (Texts.hasText(request.getDownstreamChannelCode())) {
      channelCodes.add(request.getDownstreamChannelCode());
    }
    if (EmptyChecks.isNotNull(request.getDownstreamChannelCodes())) {
      request.getDownstreamChannelCodes().stream()
          .filter(Texts::hasText)
          .map(String::trim)
          .forEach(channelCodes::add);
    }
    return List.copyOf(channelCodes);
  }
}
