package com.example.batch.orchestrator.domain.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class WorkflowNodeRunEntity {

    private Long id;
    private Long workflowRunId;
    private String nodeCode;
    private String nodeType;
    /**
     * 同一工作流节点的执行序号。
     *
     * <p>这不是重试计数，而是用于区分同一个工作流实例下，
     * 同一节点的多次执行记录。
     */
    private Integer runSeq;
    private String nodeStatus;
    /**
     * 节点执行生命周期内的重试次数。
     */
    private Integer retryCount;
    private String errorCode;
    private String errorMessage;
    private Instant startedAt;
    private Instant finishedAt;
    private Long durationMs;
}
