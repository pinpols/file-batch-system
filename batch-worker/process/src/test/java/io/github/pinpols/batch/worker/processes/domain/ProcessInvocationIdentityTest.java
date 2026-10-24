package io.github.pinpols.batch.worker.processes.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("PROCESS 已领取执行身份的构造边界")
class ProcessInvocationIdentityTest {

  @ParameterizedTest
  @ValueSource(strings = {"tenantId", "taskId", "jobInstanceId", "jobPartitionId", "workerId", "invocationId"})
  @DisplayName("缺少任一领取身份字段时拒绝构造")
  void shouldRejectMissingClaimField_whenRequiredFieldIsNull(String field) {
    ProcessInvocationIdentity.ProcessInvocationIdentityBuilder builder = completeBuilder();
    switch (field) {
      case "tenantId" -> builder.tenantId(null);
      case "taskId" -> builder.taskId(null);
      case "jobInstanceId" -> builder.jobInstanceId(null);
      case "jobPartitionId" -> builder.jobPartitionId(null);
      case "workerId" -> builder.workerId(null);
      case "invocationId" -> builder.invocationId(null);
      default -> throw new IllegalArgumentException(field);
    }
    assertThatThrownBy(builder::build).isInstanceOf(NullPointerException.class).hasMessage(field);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "  "})
  @DisplayName("空白租户、Worker 或 invocation 不能标识已领取执行")
  void shouldRejectBlankClaimIdentity_whenClaimTextIsEmptyOrWhitespace(String blank) {
    ProcessInvocationIdentity.ProcessInvocationIdentityBuilder missingTenant = completeBuilder();
    missingTenant.tenantId(blank);
    ProcessInvocationIdentity.ProcessInvocationIdentityBuilder missingWorker = completeBuilder();
    missingWorker.workerId(blank);
    ProcessInvocationIdentity.ProcessInvocationIdentityBuilder missingInvocation = completeBuilder();
    missingInvocation.invocationId(blank);

    assertThatThrownBy(missingTenant::build).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(missingWorker::build).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(missingInvocation::build).isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(longs = {0L, -1L})
  @DisplayName("任务、实例和分片必须使用已持久化的正数身份")
  void shouldRejectNonPositivePersistentIdentity_whenIdIsZeroOrNegative(long invalidId) {
    ProcessInvocationIdentity.ProcessInvocationIdentityBuilder invalidTask = completeBuilder();
    invalidTask.taskId(invalidId);
    ProcessInvocationIdentity.ProcessInvocationIdentityBuilder invalidInstance = completeBuilder();
    invalidInstance.jobInstanceId(invalidId);
    ProcessInvocationIdentity.ProcessInvocationIdentityBuilder invalidPartition = completeBuilder();
    invalidPartition.jobPartitionId(invalidId);

    assertThatThrownBy(invalidTask::build).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(invalidInstance::build).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(invalidPartition::build).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("合法领取身份原样保留且日志文本不含租户与 invocation")
  void shouldRetainClaimIdentityWithoutExposingSensitiveFields_whenClaimIsComplete() {
    ProcessInvocationIdentity.ProcessInvocationIdentityBuilder builder = completeBuilder();
    ProcessInvocationIdentity identity = builder.build();

    assertThat(identity.tenantId()).isEqualTo("tenant-process");
    assertThat(identity.taskId()).isEqualTo(11L);
    assertThat(identity.jobInstanceId()).isEqualTo(22L);
    assertThat(identity.jobPartitionId()).isEqualTo(33L);
    assertThat(identity.workerId()).isEqualTo("worker-process");
    assertThat(identity.invocationId()).isEqualTo("invocation-process");
    assertThat(identity.toString()).isEqualTo("ProcessInvocationIdentity[taskId=11]");
  }

  private ProcessInvocationIdentity.ProcessInvocationIdentityBuilder completeBuilder() {
    ProcessInvocationIdentity.ProcessInvocationIdentityBuilder builder =
        ProcessInvocationIdentity.builder()
            .tenantId("tenant-process")
            .taskId(11L)
            .jobInstanceId(22L)
            .jobPartitionId(33L)
            .workerId("worker-process")
            .invocationId("invocation-process");
    return builder;
  }
}
