package com.example.batch.orchestrator.domain.pipeline;

import lombok.Data;

import java.util.List;

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
