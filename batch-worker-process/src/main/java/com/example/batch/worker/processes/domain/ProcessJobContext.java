package com.example.batch.worker.processes.domain;

import com.example.batch.worker.core.support.ExecutionContext;
import com.example.batch.worker.processes.stage.ProcessComputePlugin;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;

/** PROCESS 任务执行上下文,贯穿各 stage 传递状态、水位和中间结果。 */
@Data
public class ProcessJobContext implements ExecutionContext {

  private String tenantId;
  private String jobCode;
  private String workerId;
  private String rawPayload;
  private Map<String, Object> attributes = new LinkedHashMap<>();

  /**
   * 在 PREPARE 阶段一次性解析出来的 plugin 实例,供后续 4 个 stage 复用,避免每个 stage 都查一次 step_definition 找 COMPUTE step
   * 的 impl_code。无匹配 plugin 时为 null,框架会用默认 stage step(全 success)兜底。
   */
  private ProcessComputePlugin resolvedPlugin;

  /** PREPARE 时生成的 staging 批次唯一键,COMPUTE/VALIDATE/COMMIT/FEEDBACK 都按它过滤 batch.process_staging 行。 */
  private String batchKey;

  /**
   * P2-3:plugin 在 PREPARE 阶段解析出的私有状态(例:sqlTransformCompute 把 SqlTransformComputeSpec 缓存这里), 后续 4
   * 段从这里读回。具体类型由 plugin 决定,框架不解读;只有当事 plugin 自己 cast。
   *
   * <p>历史上塞在 {@code attributes} 里(key={@code PROCESS_PARSED_SPEC}),但 attributes 既是 stage step
   * IO,也参与 step summary 构建,放强类型(且未必 JSON 可序列化)对象进去会污染该契约。
   */
  private Object pluginState;
}
