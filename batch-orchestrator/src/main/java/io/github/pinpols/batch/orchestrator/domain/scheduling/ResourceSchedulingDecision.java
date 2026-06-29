package io.github.pinpols.batch.orchestrator.domain.scheduling;

import io.github.pinpols.batch.common.model.WorkerRouteModel;
import lombok.Data;

@Data
public class ResourceSchedulingDecision {

  private ResourceAdmissionAction admissionAction;
  private boolean dispatchable;
  private boolean failFast;
  private String reasonCode;
  private String reasonMessage;
  private String queueCode;
  private String workerGroup;
  private Integer priority;
  private String priorityBand;
  private Long fairnessScore;
  private Integer tenantWeight;
  private Integer queueWeight;
  private Integer tenantActiveJobs;
  private Integer tenantActivePartitions;
  private Integer queueActiveJobs;
  private Integer queueActivePartitions;
  private String partitionStatus;
  private String taskStatus;
  private WorkerRouteModel route;
}
