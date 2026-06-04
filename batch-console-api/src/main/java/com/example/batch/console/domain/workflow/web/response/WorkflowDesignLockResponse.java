package com.example.batch.console.domain.workflow.web.response;

import java.time.Instant;

/**
 * Workflow DAG designer 编辑锁状态(BE Spike,docs/design/workflow-dag-designer.md)。
 *
 * <p>lockedBy = console username;expiresAt = UTC,5min 后失效。
 */
public record WorkflowDesignLockResponse(String lockedBy, Instant expiresAt) {}
