package io.github.pinpols.batch.orchestrator.domain.command;

import java.util.List;
import java.util.Map;

/**
 * 仅供内部状态推进使用的执行结果：成功产出与执行失败字段在类型上分离。
 *
 * <p>传输 command 仍保留历史字段，不收紧旧输入；成功结果中的 verifier 失败仍是软告警。
 * 动态节点输出和 evidence 保持开放，不能为了类型化封闭租户的 workflow DSL。
 */
public sealed interface TaskExecutionResult {

  record Success(
      String highWaterMarkOut, Map<String, Object> outputs, List<VerifierFailure> verifierFailures)
      implements TaskExecutionResult {}

  record Failure(
      String errorCode, String errorMessage, String errorKey, String errorArgs, String failureClass)
      implements TaskExecutionResult {}
}
