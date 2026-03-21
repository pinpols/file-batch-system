package com.example.batch.worker.core.support;

import com.example.batch.worker.core.domain.TaskExecutionReport;

public interface TaskExecutionClient {

    boolean claim(String tenantId, Long taskId, String workerId);

    boolean renewLease(String tenantId, Long taskId, String workerId);

    void report(TaskExecutionReport report);
}
