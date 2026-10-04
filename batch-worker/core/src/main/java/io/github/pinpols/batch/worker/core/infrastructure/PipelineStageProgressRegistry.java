package io.github.pinpols.batch.worker.core.infrastructure;

import io.github.pinpols.batch.common.dto.WorkerPipelineProgressDto;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.worker.core.support.ExecutionContext;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 保存当前 Worker 进程内所有并发 pipeline stage 的行级进度。
 *
 * <p>注册表按 task/pipeline/stage 隔离，而不是按 JVM 保存一个全局槽。Worker 默认允许并发多个 CLAIM，单槽会让最后更新的任务
 * 覆盖其他任务，并把错误的行数展示到同一 worker 上运行的其他 pipeline。
 */
@Component
public class PipelineStageProgressRegistry {

  private final Map<Key, WorkerPipelineProgressDto> snapshots = new ConcurrentHashMap<>();

  public void publish(
      ExecutionContext context, String stageCode, long rowsProcessed, Long totalRowsHint) {
    Key key = key(context, stageCode);
    if (EmptyChecks.isNull(key)) {
      return;
    }
    snapshots.put(
        key,
        new WorkerPipelineProgressDto(
            key.taskId(), key.pipelineInstanceId(), key.stageCode(), rowsProcessed, totalRowsHint));
  }

  public void clear(ExecutionContext context, String stageCode) {
    Key key = key(context, stageCode);
    if (EmptyChecks.isNotNull(key)) {
      snapshots.remove(key);
    }
  }

  public List<WorkerPipelineProgressDto> snapshots() {
    return snapshots.values().stream()
        .sorted(Comparator.comparing(WorkerPipelineProgressDto::pipelineInstanceId)
            .thenComparing(WorkerPipelineProgressDto::taskId)
            .thenComparing(WorkerPipelineProgressDto::stageCode))
        .toList();
  }

  private static Key key(ExecutionContext context, String stageCode) {
    if (EmptyChecks.isNull(context) || !Texts.hasText(stageCode)) {
      return null;
    }
    Long taskId = longValue(context.getAttributes().get(PipelineRuntimeKeys.TASK_ID));
    Long pipelineInstanceId =
        longValue(context.getAttributes().get(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID));
    if (EmptyChecks.isNull(taskId) || EmptyChecks.isNull(pipelineInstanceId)) {
      return null;
    }
    return new Key(taskId, pipelineInstanceId, stageCode);
  }

  private static Long longValue(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (EmptyChecks.isNull(value)) {
      return null;
    }
    try {
      return Long.valueOf(String.valueOf(value));
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private record Key(Long taskId, Long pipelineInstanceId, String stageCode) {}
}
