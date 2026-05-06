package com.example.batch.orchestrator.domain.command;

import com.example.batch.common.i18n.LocalizedErrorCarrier;
import java.util.Map;
import lombok.Builder;

@Builder
public record TaskOutcomeCommand(
    String tenantId,
    Long taskId,
    String workerId,
    boolean success,
    String resultSummary,
    String errorCode,
    String errorMessage,
    /** Worker BizException.of 的 i18n key;持久化到 job_task.error_key 供 console 重渲染。 */
    String errorKey,
    /** i18n 占位符参数 JSON 数组,与 errorKey 一起跨进程传递。 */
    String errorArgs,
    /**
     * 增量执行模式(ExecutionMode.INCREMENTAL)下,worker 完成时上报的新水位高点。orchestrator 在成功路径下回写 {@code
     * job_instance.high_water_mark_out};非 INCREMENTAL 或 worker 没显式上报时为 null,持久化也跳过(保留旧值,下次实例的 IN
     * 不变即"无进展")。
     */
    String highWaterMarkOut,
    /**
     * ADR-009 Stage 1.2: worker 上报的节点产出 Map(JSON 原生类型)。orchestrator 在 success 路径下序列化为 JSON 写入
     * workflow_node_run.output,供下游 workflow 节点 $.nodes.&lt;X&gt;.output.&lt;key&gt; DSL 引用。null
     * 等价无产出。
     */
    Map<String, Object> outputs,
    /**
     * ADR-014: optional; non-null must equal {@code job_partition.current_invocation_id} when
     * partition exists.
     */
    String partitionInvocationId,
    /**
     * ADR-012 worker 上报的失败分类（V111）。仅 {@code success=false} 路径有意义；为空时由 orchestrator 端 {@code
     * FailureClassifier} 兜底推断。允许的取值见 {@link com.example.batch.common.enums.FailureClass}。
     */
    String failureClass)
    implements LocalizedErrorCarrier {

  // record 默认 accessor 是 errorMessage() 无 get 前缀;桥接 carrier 契约的 getErrorXxx() bean 命名。
  @Override
  public String getErrorMessage() {
    return errorMessage;
  }

  @Override
  public String getErrorKey() {
    return errorKey;
  }

  @Override
  public String getErrorArgs() {
    return errorArgs;
  }
}
