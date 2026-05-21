package com.example.batch.worker.core.support;

import java.util.Map;

/**
 * 各业务域 pipeline 阶段上下文的公共接口。
 *
 * <p>这是 Worker 侧执行上下文，非 Orchestrator 侧工作流上下文。
 *
 * <p>三个 Worker 域（import / export / dispatch）均包含相同的基础字段。 实现此接口可让 {@link AbstractStageExecutor}
 * 无需反射即可访问这些字段， 保证泛型阶段循环在三条 pipeline 上都是编译期安全的。
 */
public interface ExecutionContext {

  String getTenantId();

  String getJobCode();

  String getWorkerId();

  /** 用于在 pipeline 阶段间共享状态的可变属性集合，键定义于 {@link PipelineRuntimeKeys}。 */
  Map<String, Object> getAttributes();

  /**
   * 以下 setter 是 4 个 *JobContext 共有的写路径（已由 Lombok {@code @Data} 自动生成）， 提到接口上让 {@link
   * AbstractPipelineStepExecutionAdapter#populateCommonFields} 能以泛型方式回填， 避免每个 {@code buildContext}
   * 重复 5 行 setter 模板。
   *
   * <p>新增非 file 类 ExecutionContext 实现时，照样 {@code @Data} 即可满足这些方法。
   */
  void setTenantId(String tenantId);

  void setJobCode(String jobCode);

  void setWorkerId(String workerId);

  void setRawPayload(String rawPayload);

  void setAttributes(Map<String, Object> attributes);

  String getRawPayload();
}
