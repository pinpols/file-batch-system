package io.github.pinpols.batch.worker.core.infrastructure;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Pipeline 运行代际：重派会复用同一 {@code pipeline_instance} 行并重置 {@code started_at}，旧 invocation 持有的
 * {@code startedAt} 随之失配，其迟到的阶段/终态写入不再覆盖新执行。
 */
public record PipelineRunFence(Long pipelineInstanceId, OffsetDateTime startedAt) {

  public static PipelineRunFence fromAttributes(Map<String, Object> attributes) {
    Long pipelineInstanceId =
        PlatformRuntimeValues.toLong(attributes.get(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID));
    if (pipelineInstanceId == null) {
      return null;
    }
    Object startedAt = attributes.get(PipelineRuntimeKeys.PIPELINE_RUN_STARTED_AT);
    return new PipelineRunFence(
        pipelineInstanceId, startedAt instanceof OffsetDateTime value ? value : null);
  }
}
