package io.github.pinpols.batch.worker.core.support;

import io.github.pinpols.batch.common.kafka.TaskDispatchMessage;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.worker.core.application.TaskDispatchExecutor;
import io.github.pinpols.batch.worker.core.application.TaskDispatchExecutor.BatchItemExecution;
import io.github.pinpols.batch.worker.core.domain.WorkerExecutionResult;
import io.github.pinpols.batch.worker.core.domain.WorkerRegistration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * 批量消费的执行协调器。
 *
 * <p>批量 listener 的 offset 语义要求按租户分组执行：RLS 上下文一次只能绑定一个租户，且批内某条业务失败时只能将该条送入 DLQ，
 * 不能把同一 poll 中的正常消息一起丢弃。因此这里集中处理解码、租户分组、批量执行结果归因和逐条 DLQ 决策；Kafka 背压和 offset 提交仍由
 * {@link AbstractTaskConsumer} 控制。
 */
@Slf4j
final class TaskConsumerBatchExecutionCoordinator {

  private final Supplier<String> workerTypeSupplier;
  private final Supplier<TaskDispatchExecutor> executorSupplier;
  private final BiPredicate<TaskDispatchMessage, WorkerRegistration> acceptsMessage;
  private final BiFunction<String, String, Boolean> publishToDlq;

  TaskConsumerBatchExecutionCoordinator(
      Supplier<String> workerTypeSupplier,
      Supplier<TaskDispatchExecutor> executorSupplier,
      BiPredicate<TaskDispatchMessage, WorkerRegistration> acceptsMessage,
      BiFunction<String, String, Boolean> publishToDlq) {
    this.workerTypeSupplier = workerTypeSupplier;
    this.executorSupplier = executorSupplier;
    this.acceptsMessage = acceptsMessage;
    this.publishToDlq = publishToDlq;
  }

  /** 解码并执行一批消息，返回值表示整批是否可以提交 offset。 */
  boolean process(List<String> payloads, WorkerRegistration registration) {
    Map<String, List<BatchPayload>> byTenant = new LinkedHashMap<>();
    for (String payload : payloads) {
      TaskDispatchMessage message;
      try {
        message = JsonUtils.fromJson(payload, TaskDispatchMessage.class);
      } catch (Exception parseEx) {
        log.error(
            "{} batch payload parse failed — publishing only this payload to DLQ: error={}",
            workerTypeSupplier.get(),
            parseEx.getMessage(),
            parseEx);
        if (!publishToDlq.apply(payload, parseEx.getMessage())) {
          return false;
        }
        continue;
      }
      if (!acceptsMessage.test(message, registration)) {
        continue;
      }
      byTenant
          .computeIfAbsent(message.tenantId(), ignored -> new ArrayList<>())
          .add(new BatchPayload(payload, message));
    }
    return processBatchGroups(byTenant, registration.getWorkerId());
  }

  private boolean processBatchGroups(Map<String, List<BatchPayload>> byTenant, String workerId) {
    boolean allDlq = true;
    for (Map.Entry<String, List<BatchPayload>> entry : byTenant.entrySet()) {
      String tenantId = entry.getKey();
      List<BatchPayload> group = entry.getValue();
      List<TaskDispatchMessage> messages =
          group.stream().map(BatchPayload::message).toList();
      List<BatchItemExecution> executions = executeBatchForTenant(tenantId, messages, workerId);
      for (BatchItemExecution execution : executions) {
        if (execution == null || execution.skipped()) {
          continue;
        }
        if (execution.error() == null) {
          logBatchSuccess(execution.result());
          continue;
        }
        if (TaskConsumerFailurePolicy.isTransientOrchestratorFailure(execution.error())) {
          log.warn(
              "{} batch item transient failure (5xx/network) — NOT committing, will retry whole"
                  + " batch: taskId={}, error={}",
              workerTypeSupplier.get(),
              execution.message() == null ? null : execution.message().taskId(),
              execution.error().getMessage());
          return false;
        }
        String payload = originalPayload(execution, group);
        if (payload == null) {
          log.error(
              "{} batch item has no matching original payload — refusing to commit offset:"
                  + " messageIndex={}, taskId={}",
              workerTypeSupplier.get(),
              execution.messageIndex(),
              execution.message() == null ? null : execution.message().taskId());
          allDlq = false;
          continue;
        }
        log.error(
            "{} batch item execution failed — publishing only this payload to DLQ: taskId={},"
                + " error={}",
            workerTypeSupplier.get(),
            execution.message() == null ? null : execution.message().taskId(),
            execution.error().getMessage(),
            execution.error());
        if (!publishToDlq.apply(payload, execution.error().getMessage())) {
          allDlq = false;
        }
      }
    }
    return allDlq;
  }

  private List<BatchItemExecution> executeBatchForTenant(
      String tenantId, List<TaskDispatchMessage> messages, String workerId) {
    if (EmptyChecks.isNotBlank(tenantId) && !"unknown".equals(tenantId)) {
      return RlsTenantContextHolder.runWithTenant(
          tenantId, () -> executorSupplier.get().executeBatchDetailed(messages, workerId));
    }
    return executorSupplier.get().executeBatchDetailed(messages, workerId);
  }

  private void logBatchSuccess(WorkerExecutionResult result) {
    if (result != null) {
      log.info(
          "{} batch task processed: taskId={}, success={}, message={}",
          workerTypeSupplier.get(),
          result.taskId(),
          result.success(),
          result.message());
    }
  }

  private String originalPayload(BatchItemExecution execution, List<BatchPayload> group) {
    int index = execution.messageIndex();
    if (index >= 0 && index < group.size()) {
      return group.get(index).payload();
    }
    // Compatibility for test/custom executors that still construct an unindexed result.
    for (BatchPayload item : group) {
      if (item.message() == execution.message()) {
        return item.payload();
      }
    }
    return null;
  }

  private record BatchPayload(String payload, TaskDispatchMessage message) {}
}
