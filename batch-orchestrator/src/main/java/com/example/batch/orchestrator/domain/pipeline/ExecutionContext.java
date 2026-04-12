package com.example.batch.orchestrator.domain.pipeline;

import com.example.batch.common.model.WorkerRouteModel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * Orchestrator 侧的工作流执行上下文。
 *
 * <p>这是 orchestrator 内部使用的标准 pipeline 上下文，承载 pipeline 定义、业务日期、traceId 以及步骤执行结果等工作流相关字段。
 */
@Data
public class ExecutionContext {

  private String tenantId;
  private String jobCode;
  private String bizDate;
  private String traceId;
  private PipelineDefinition pipelineDefinition;
  private Map<String, Object> attributes = new LinkedHashMap<>();
  private List<StepResult> stepResults;
  private WorkerRouteModel defaultWorkerRoute;

  public String getPipelineCode() {
    return jobCode;
  }

  public void setPipelineCode(String pipelineCode) {
    this.jobCode = pipelineCode;
  }
}
