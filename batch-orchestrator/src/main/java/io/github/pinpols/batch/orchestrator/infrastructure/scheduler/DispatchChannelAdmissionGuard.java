package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import io.github.pinpols.batch.common.utils.EmptyValues;
import io.github.pinpols.batch.orchestrator.application.scheduler.DownstreamAdmissionGuard;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceCheck;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceSchedulingRequest;
import io.github.pinpols.batch.orchestrator.mapper.DownstreamAdmissionMapper;

/** 使用 dispatch worker 写入的平台健康快照，在生成 Kafka 消息前阻断已知故障渠道。 */
@Component
@RequiredArgsConstructor
public class DispatchChannelAdmissionGuard implements DownstreamAdmissionGuard {

    private final DownstreamAdmissionMapper downstreamAdmissionMapper;

    @Override
    public ResourceCheck check(ResourceSchedulingRequest request) {
        boolean dispatchAdmissionIsNotApplicable = request == null
                || !"DISPATCH".equalsIgnoreCase(request.getWorkerType())
                || !EmptyValues.hasText(request.getTenantId());
        if (dispatchAdmissionIsNotApplicable) {
            return ResourceCheck.allow();
        }
        List<String> channelCodes = resolveChannelCodes(request);
        if (EmptyValues.isEmpty(channelCodes)) {
            return ResourceCheck.allow();
        }
        if (downstreamAdmissionMapper.isAnyDispatchChannelBlocked(request.getTenantId(), channelCodes)) {
            return ResourceCheck.waitForCapacity(
                    "DOWNSTREAM_CHANNEL_UNAVAILABLE",
                    "dispatch channel is unhealthy and waiting for a successful health probe");
        }
        return ResourceCheck.allow();
    }

    private List<String> resolveChannelCodes(ResourceSchedulingRequest request) {
        Set<String> channelCodes = new LinkedHashSet<>();
        if (EmptyValues.hasText(request.getDownstreamChannelCode())) {
            channelCodes.add(request.getDownstreamChannelCode());
        }
        if (request.getDownstreamChannelCodes() != null) {
            request.getDownstreamChannelCodes().stream()
                    .filter(EmptyValues::hasText)
                    .map(String::trim)
                    .forEach(channelCodes::add);
        }
        return List.copyOf(channelCodes);
    }
}
