package io.github.pinpols.batch.orchestrator.application.service.task;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("分发结果关联文件遵循主字段和束字段的明确优先级")
class TaskOutcomePayloadSupportBundleTest {

  @Test
  @DisplayName("分发文件关联不受 JSON 字段顺序影响")
  void shouldResolveDispatchFileWithoutDependingOnJsonOrder() {
    JobTaskEntity task = new JobTaskEntity();
    task.setTaskType("DISPATCH");
    task.setTaskPayload("{\"fileId\":77,\"bundleSourceFileId\":42}");
    assertThat(TaskOutcomePayloadSupport.resolveRelatedFileId(task)).isEqualTo(42L);
    task.setTaskPayload("{\"bundleSourceFileId\":42,\"fileId\":77}");
    assertThat(TaskOutcomePayloadSupport.resolveRelatedFileId(task)).isEqualTo(42L);
    task.setTaskPayload("{\"bundleSourceFileId\":42}");
    assertThat(TaskOutcomePayloadSupport.resolveRelatedFileId(task)).isEqualTo(42L);
    task.setTaskPayload("{\"fileId\":\"invalid\",\"bundleSourceFileId\":42}");
    assertThat(TaskOutcomePayloadSupport.resolveRelatedFileId(task)).isEqualTo(42L);
  }

  @Test
  @DisplayName("分数文件标识不会关联到其他文件")
  void shouldNotAssociateFractionalFileIdWithAnotherFile() {
    JobTaskEntity task = new JobTaskEntity();
    task.setTaskType("DISPATCH");
    task.setTaskPayload("{\"fileId\":42.9}");

    assertThat(TaskOutcomePayloadSupport.resolveRelatedFileId(task)).isNull();
    task.setTaskPayload("{\"fileId\":42.00000000000000001}");
    assertThat(TaskOutcomePayloadSupport.resolveRelatedFileId(task)).isNull();
    task.setTaskPayload("{\"fileId\":18446744073709551658}");
    assertThat(TaskOutcomePayloadSupport.resolveRelatedFileId(task)).isNull();
    task.setTaskPayload("{\"fileId\":\"42\"}");
    assertThat(TaskOutcomePayloadSupport.resolveRelatedFileId(task)).isEqualTo(42L);
  }
}
