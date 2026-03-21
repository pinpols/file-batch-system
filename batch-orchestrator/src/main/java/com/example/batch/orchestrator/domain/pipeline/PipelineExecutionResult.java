package com.example.batch.orchestrator.domain.pipeline;

import java.util.List;

public class PipelineExecutionResult {

    private String pipelineCode;
    private String runStatus;
    private String message;
    private List<StepResult> stepResults;

    public String getPipelineCode() {
        return pipelineCode;
    }

    public void setPipelineCode(String pipelineCode) {
        this.pipelineCode = pipelineCode;
    }

    public String getRunStatus() {
        return runStatus;
    }

    public void setRunStatus(String runStatus) {
        this.runStatus = runStatus;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public List<StepResult> getStepResults() {
        return stepResults;
    }

    public void setStepResults(List<StepResult> stepResults) {
        this.stepResults = stepResults;
    }
}
