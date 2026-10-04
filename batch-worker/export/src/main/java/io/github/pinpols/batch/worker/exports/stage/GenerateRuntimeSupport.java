package io.github.pinpols.batch.worker.exports.stage;

import io.github.pinpols.batch.worker.core.config.WorkerCheckpointProperties;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineStageProgressRegistry;
import io.github.pinpols.batch.worker.core.infrastructure.checkpoint.ProcessingPositionStore;
import io.github.pinpols.batch.worker.exports.stage.format.GenerateCursorCodec;
import org.springframework.stereotype.Component;

/**
 * 汇集 GENERATE 阶段的续跑与进度运行时协作者。
 *
 * <p>这些对象共同维护一次生成任务的跨页状态，但不参与格式选择和业务数据读取。集中它们可以让 {@link GenerateStep}
 * 只依赖一个运行时边界，并避免后续每增加一种位点或进度能力都继续扩大阶段构造器。
 */
@Component
public final class GenerateRuntimeSupport {

  private final WorkerCheckpointProperties checkpointProperties;
  private final ProcessingPositionStore positionStore;
  private final GenerateCursorCodec cursorCodec;
  private final PipelineStageProgressRegistry progressRegistry;

  public GenerateRuntimeSupport(
      WorkerCheckpointProperties checkpointProperties,
      ProcessingPositionStore positionStore,
      GenerateCursorCodec cursorCodec,
      PipelineStageProgressRegistry progressRegistry) {
    this.checkpointProperties = checkpointProperties;
    this.positionStore = positionStore;
    this.cursorCodec = cursorCodec;
    this.progressRegistry = progressRegistry;
  }

  WorkerCheckpointProperties checkpointProperties() {
    return checkpointProperties;
  }

  ProcessingPositionStore positionStore() {
    return positionStore;
  }

  GenerateCursorCodec cursorCodec() {
    return cursorCodec;
  }

  PipelineStageProgressRegistry progressRegistry() {
    return progressRegistry;
  }
}
