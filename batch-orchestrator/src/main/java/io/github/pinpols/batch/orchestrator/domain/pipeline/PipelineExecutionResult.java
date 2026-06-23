package io.github.pinpols.batch.orchestrator.domain.pipeline;

import java.util.List;
import lombok.Data;

@Data
public class PipelineExecutionResult {

  private String jobCode;
  private String runStatus;
  private String message;
  private List<StepResult> stepResults;

  public String getPipelineCode() {
    return jobCode;
  }

  public void setPipelineCode(String pipelineCode) {
    this.jobCode = pipelineCode;
  }
}
