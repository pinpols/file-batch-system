package com.example.batch.console.domain.query;

import com.example.batch.common.model.PageRequest;

public record WorkflowNodeRunQuery(
        Long workflowRunId, String nodeCode, String nodeStatus, PageRequest pageRequest) {}
