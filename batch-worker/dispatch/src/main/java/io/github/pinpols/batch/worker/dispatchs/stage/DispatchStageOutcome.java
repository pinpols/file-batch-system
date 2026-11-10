package io.github.pinpols.batch.worker.dispatchs.stage;

import io.github.pinpols.batch.common.enums.TaskErrorCodes;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchStage;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchStageResult;
import java.util.List;

/** 判定分发重试后的最终结果，同时保留每个阶段的原始记录。 */
public final class DispatchStageOutcome {

  private DispatchStageOutcome() {}

  /** 结果不明或密钥不可用时不能按常规失败分支继续执行。 */
  public static boolean isTerminalFailure(DispatchStageResult result) {
    return TaskErrorCodes.EXTERNAL_OUTCOME_UNKNOWN.equals(result.code())
        || "error.dispatch.secret.blocked".equals(result.errorKey());
  }

  /** 返回未恢复的失败；只有实际成功的重试才能消除其直接对应的发送失败。 */
  public static DispatchStageResult failure(
      List<DispatchStageResult> results, boolean retryRecovered) {
    if (EmptyChecks.isEmpty(results)) {
      return null;
    }
    int recoveredFailure = recoveredFailureIndex(results, retryRecovered);
    for (DispatchStageResult result : results) {
      if (!result.success() && isTerminalFailure(result)) {
        return result;
      }
    }
    for (int index = 0; index < results.size(); index++) {
      DispatchStageResult result = results.get(index);
      if (!result.success() && index != recoveredFailure) {
        return result;
      }
    }
    return null;
  }

  private static int recoveredFailureIndex(
      List<DispatchStageResult> results, boolean retryRecovered) {
    if (!retryRecovered) {
      return -1;
    }
    for (int index = results.size() - 1; index >= 0; index--) {
      DispatchStageResult retry = results.get(index);
      if (retry.stage() != DispatchStage.RETRY || !retry.success()) {
        continue;
      }
      boolean acknowledged = false;
      boolean completed = false;
      boolean laterFailure = false;
      for (int next = index + 1; next < results.size(); next++) {
        DispatchStageResult result = results.get(next);
        laterFailure |= !result.success();
        acknowledged |= result.success() && result.stage() == DispatchStage.ACK;
        completed |= result.success() && result.stage() == DispatchStage.COMPLETE;
      }
      // 重试成功并不等于确认或最终完成；缺少收尾且没有更具体的失败时保留原发送失败。
      if (!laterFailure && (!acknowledged || !completed)) {
        return -1;
      }
      for (int prior = index - 1; prior >= 0; prior--) {
        DispatchStageResult result = results.get(prior);
        if (!result.success()) {
          return result.stage() == DispatchStage.DISPATCH
                  && "DISPATCH_SEND_FAILED".equals(result.code())
              ? prior
              : -1;
        }
      }
      break;
    }
    return -1;
  }
}
