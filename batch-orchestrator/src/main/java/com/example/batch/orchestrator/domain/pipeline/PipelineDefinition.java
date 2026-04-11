package com.example.batch.orchestrator.domain.pipeline;

import lombok.Data;

import java.util.List;

/**
 * Orchestrator 侧的 pipeline 定义模型。
 *
 * <p>标准业务主键是 {@code jobCode}。 {@code pipelineCode} 作为兼容别名保留，供旧调用方和序列化载荷继续使用。
 */
@Data
public class PipelineDefinition {

    private String jobCode;
    private String pipelineName;
    private String pipelineType;
    private String defaultWorkerType;
    private Boolean enabled;
    private List<StepDefinition> steps;

    public String getPipelineCode() {
        return jobCode;
    }

    public void setPipelineCode(String pipelineCode) {
        this.jobCode = pipelineCode;
    }
}
