package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDefinitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceSchedulingRequest;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WaitingPartitionDispatchSchedulerRequestTest {

  @Test
  @DisplayName("WAITING 重派保留资源池与下游通道约束")
  void waitingRetryKeepsResourceAndDownstreamAdmissionFields() {
    JobInstanceEntity instance = new JobInstanceEntity();
    instance.setTenantId("ta");
    instance.setJobCode("dispatch-job");
    instance.setQueueCode("workflow-queue");
    instance.setWorkerGroup("DISPATCH");
    instance.setPriority(8);

    JobPartitionEntity partition = new JobPartitionEntity();
    partition.setWorkerGroup("dispatch-pool");
    partition.setInputSnapshot(JsonUtils.toJson(Map.of(
        "queueCode", "dispatch-queue",
        "resourceProfile", "io-heavy",
        "downstreamChannelCode", "sftp-primary",
        "windowCode", "night-window")));

    JobTaskEntity task = new JobTaskEntity();
    task.setTaskType("DISPATCH");

    JobDefinitionEntity definition = JobDefinitionEntity.builder()
        .id(1L)
        .tenantId("ta")
        .jobCode("dispatch-job")
        .jobName("dispatch-job")
        .jobType("DISPATCH")
        .windowCode("fallback-window")
        .build();

    ResourceSchedulingRequest request =
        WaitingPartitionDispatchScheduler.buildRequest(instance, partition, task, definition);

    assertThat(request.getQueueCode()).isEqualTo("dispatch-queue");
    assertThat(request.getWorkerGroup()).isEqualTo("dispatch-pool");
    assertThat(request.getResourceProfile()).isEqualTo("io-heavy");
    assertThat(request.getDownstreamChannelCode()).isEqualTo("sftp-primary");
    assertThat(request.getWindowCode()).isEqualTo("night-window");
  }
}
