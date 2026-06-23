package io.github.pinpols.batch.orchestrator.application.service.task;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowDagService;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 为已启动的 Job 创建分区、任务及 Outbox 事件，在独立事务（T2）中执行，与 Job 实例创建事务（T1）分离以降低锁持有时长。 从 {@link
 * io.github.pinpols.batch.orchestrator.service.DefaultLaunchService} 中拆分。
 */
public interface PartitionDispatchService {

  // T1 产物：启动请求携带的调用方参数，在 T2 中只读使用
  record DispatchRequest(
      LaunchRequest request, Map<String, Object> effectiveParams, String traceId) {}

  // T1 产物：T1 事务写库后得到的实体引用，T2 据此创建分区和任务
  record DispatchRuntime(
      JobInstanceEntity jobInstance,
      WorkflowRunEntity workflowRun,
      List<WorkflowDagService.DagNodeResolution> initialNodes,
      Instant startedAt) {}

  /**
   * 将 T1 的请求参数（DispatchRequest）与 T1 写库结果（DispatchRuntime）合并为单一上下文， 避免 dispatch() 方法参数列表超过 6
   * 个。两部分分开定义是因为生命周期不同： request 来自调用方，runtime 来自 T1 事务结果。
   */
  final class DispatchContext {

    private final DispatchRequest requestContext;
    private final DispatchRuntime runtimeContext;

    private DispatchContext(DispatchRequest requestContext, DispatchRuntime runtimeContext) {
      this.requestContext = requestContext;
      this.runtimeContext = runtimeContext;
    }

    public static DispatchContext of(
        DispatchRequest requestContext, DispatchRuntime runtimeContext) {
      return new DispatchContext(requestContext, runtimeContext);
    }

    public LaunchRequest request() {
      return requestContext.request();
    }

    public Map<String, Object> effectiveParams() {
      return requestContext.effectiveParams();
    }

    public String traceId() {
      return requestContext.traceId();
    }

    public JobInstanceEntity jobInstance() {
      return runtimeContext.jobInstance();
    }

    public WorkflowRunEntity workflowRun() {
      return runtimeContext.workflowRun();
    }

    public List<WorkflowDagService.DagNodeResolution> initialNodes() {
      return runtimeContext.initialNodes();
    }

    public Instant startedAt() {
      return runtimeContext.startedAt();
    }
  }

  /** 构建调度计划，创建分区、任务，写入 Outbox 事件，并将 Job 实例标记为 RUNNING，在独立事务中执行。 */
  void dispatch(DispatchContext context);
}
