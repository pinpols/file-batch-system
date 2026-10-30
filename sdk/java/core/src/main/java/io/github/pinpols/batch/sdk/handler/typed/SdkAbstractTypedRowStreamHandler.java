package io.github.pinpols.batch.sdk.handler.typed;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.sdk.checkpoint.SdkCheckpoint;
import io.github.pinpols.batch.sdk.checkpoint.SdkCheckpointState;
import io.github.pinpols.batch.sdk.handler.SdkAbstractTaskHandler;
import io.github.pinpols.batch.sdk.handler.SdkRowResult;
import io.github.pinpols.batch.sdk.task.SdkTaskContext;
import io.github.pinpols.batch.sdk.task.SdkTaskResult;
import java.util.Map;
import java.util.Optional;

/** 类型化行流模板共用的参数、检查点和输出处理逻辑。 */
abstract class SdkAbstractTypedRowStreamHandler<I, O, R> extends SdkAbstractTaskHandler {

  private final SdkTypedParameters<I> parameters;

  protected SdkAbstractTypedRowStreamHandler(ObjectMapper objectMapper, Class<?> declaringBase) {
    this.parameters = SdkTypedParameters.forHandler(objectMapper, this, declaringBase, 0);
  }

  protected final I parseInput(SdkTaskContext ctx) {
    return parameters.parse(ctx);
  }

  protected final Optional<SdkTaskResult> restoreCheckpoint(
      SdkTaskContext ctx, SdkRowResult counts, String completedMessage) {
    SdkCheckpoint checkpoint = ctx.checkpoint();
    String taskId = String.valueOf(ctx.taskId());
    Optional<SdkCheckpointState> resumed = checkpoint.load(taskId);
    if (resumed.map(SdkCheckpointState::completed).orElse(false)) {
      return Optional.of(SdkTaskResult.ok(completedMessage));
    }
    resumed.ifPresent(state -> {
      counts.addSuccess(state.succeedCount());
      ctx.commitCoordinator().restoreCounts(state.succeedCount(), state.failCount());
    });
    return Optional.empty();
  }

  protected final SdkTaskResult result(
      I input, SdkRowResult counts, String defaultMessage, O output) {
    if (output == null) {
      return SdkTaskResult.ok(defaultMessage, counts.toOutput());
    }
    Map<String, Object> outputMap = parameters.toOutputMap(output);
    return SdkTaskResult.ok(defaultMessage, outputMap);
  }

  protected final Map<String, Object> emptyBreakPosition() {
    return Map.of();
  }
}
