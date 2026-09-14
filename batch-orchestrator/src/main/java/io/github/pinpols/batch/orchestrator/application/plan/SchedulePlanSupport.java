package io.github.pinpols.batch.orchestrator.application.plan;

import io.github.pinpols.batch.common.constants.WorkerCapabilities;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceSchedulingDecision;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceSchedulingRequest;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Shared conversion of a schedule plan to and from resource-admission decisions. */
public final class SchedulePlanSupport {

  private SchedulePlanSupport() {}

  public static ResourceSchedulingRequest toSchedulingRequest(SchedulePlan plan) {
    ResourceSchedulingRequest request = new ResourceSchedulingRequest();
    request.setTenantId(plan.getTenantId());
    request.setJobCode(plan.getJobCode());
    request.setQueueCode(plan.getQueueCode());
    request.setWorkerGroup(plan.getWorkerGroup());
    request.setResourceProfile(plan.getResourceProfile());
    request.setDownstreamChannelCode(plan.getDownstreamChannelCode());
    request.setDownstreamChannelCodes(resolveDownstreamChannelCodes(plan));
    request.setWorkerType(plan.getDefaultWorkerType());
    request.setRequiredCapability(plan.isDryRun() ? WorkerCapabilities.DRY_RUN_SAFE : null);
    request.setWindowCode(plan.getWindowCode());
    request.setPriority(plan.getPriority());
    request.setRequestedPartitionCount(
        plan.getPartitionCount() == null ? 1 : plan.getPartitionCount());
    return request;
  }

  /**
   * 普通 DISPATCH 使用计划级渠道，BUNDLE_DISPATCH 和 fan-out 使用分区级 targetRef。
   * 两者合并去重后一次性参加准入，避免束内任一故障渠道绕过健康闸门。
   */
  private static List<String> resolveDownstreamChannelCodes(SchedulePlan plan) {
    if (EmptyChecks.isNull(plan) || !"DISPATCH".equalsIgnoreCase(plan.getDefaultWorkerType())) {
      return List.of();
    }
    Set<String> channels = new LinkedHashSet<>();
    if (hasText(plan.getDownstreamChannelCode())) {
      channels.add(plan.getDownstreamChannelCode());
    }
    if (EmptyChecks.isNotNull(plan.getPartitions())) {
      plan.getPartitions().stream()
          .map(SchedulePlan.PartitionPlan::getTargetRef)
          .filter(SchedulePlanSupport::hasText)
          .forEach(channels::add);
    }
    return List.copyOf(channels);
  }

  public static void applySchedulingDecision(
      SchedulePlan plan, ResourceSchedulingDecision decision) {
    if (plan == null || decision == null) {
      return;
    }
    if (hasText(decision.getQueueCode())) {
      plan.setQueueCode(decision.getQueueCode());
    }
    if (hasText(decision.getWorkerGroup())) {
      plan.setWorkerGroup(decision.getWorkerGroup());
    }
    if (decision.getPriority() != null) {
      plan.setPriority(decision.getPriority());
    }
    if (decision.getRoute() != null) {
      plan.setDefaultWorkerRoute(decision.getRoute());
    }
    if (plan.getPartitions() == null) {
      return;
    }
    for (SchedulePlan.PartitionPlan partitionPlan : plan.getPartitions()) {
      partitionPlan.setPartitionStatus(decision.getPartitionStatus());
      if (decision.getRoute() != null) {
        partitionPlan.setWorkerRoute(decision.getRoute());
      }
    }
  }

  private static boolean hasText(String value) {
    return EmptyChecks.isNotBlank(value);
  }
}
