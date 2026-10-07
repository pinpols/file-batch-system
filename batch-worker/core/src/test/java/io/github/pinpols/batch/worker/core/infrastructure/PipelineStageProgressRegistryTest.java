package io.github.pinpols.batch.worker.core.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.dto.WorkerPipelineProgressDto;
import io.github.pinpols.batch.worker.core.support.ExecutionContext;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("流水线阶段进度注册表: 并发任务隔离与不可识别进度的过滤")
class PipelineStageProgressRegistryTest {

  private final PipelineStageProgressRegistry registry = new PipelineStageProgressRegistry();

  @Test
  @DisplayName("清理目标任务进度时不影响其它并发任务, 快照只保留剩余任务")
  void shouldKeepConcurrentTasksIsolated_whenClearingTargetTask() {
    TestContext first = context(11L, 101L);
    TestContext second = context(12L, 102L);

    registry.publish(first, "LOAD", 20L, null);
    registry.publish(second, "LOAD", 90L, 100L);
    registry.clear(first, "LOAD");

    assertThat(registry.snapshots())
        .containsExactly(new WorkerPipelineProgressDto(12L, 102L, "LOAD", 90L, 100L));
  }

  @Test
  @DisplayName("缺少稳定任务标识时不上报进度, 快照为空")
  void shouldIgnoreProgress_whenTaskIdentityIsMissing() {
    TestContext context = new TestContext();
    context.getAttributes().put(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID, 101L);

    registry.publish(context, "LOAD", 20L, null);

    assertThat(registry.snapshots()).isEmpty();
  }

  private static TestContext context(long taskId, long pipelineInstanceId) {
    TestContext context = new TestContext();
    context.getAttributes().put(PipelineRuntimeKeys.TASK_ID, taskId);
    context.getAttributes().put(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID, pipelineInstanceId);
    return context;
  }

  private static final class TestContext implements ExecutionContext {
    private String tenantId;
    private String jobCode;
    private String workerId;
    private String rawPayload;
    private Map<String, Object> attributes = new LinkedHashMap<>();

    @Override
    public String getTenantId() {
      return tenantId;
    }

    @Override
    public String getJobCode() {
      return jobCode;
    }

    @Override
    public String getWorkerId() {
      return workerId;
    }

    @Override
    public Map<String, Object> getAttributes() {
      return attributes;
    }

    @Override
    public void setTenantId(String tenantId) {
      this.tenantId = tenantId;
    }

    @Override
    public void setJobCode(String jobCode) {
      this.jobCode = jobCode;
    }

    @Override
    public void setWorkerId(String workerId) {
      this.workerId = workerId;
    }

    @Override
    public void setRawPayload(String rawPayload) {
      this.rawPayload = rawPayload;
    }

    @Override
    public void setAttributes(Map<String, Object> attributes) {
      this.attributes = attributes;
    }

    @Override
    public String getRawPayload() {
      return rawPayload;
    }
  }
}
