package io.github.pinpols.batch.orchestrator.application.engine;

import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.event.DomainEvent;
import io.github.pinpols.batch.common.event.DomainEventPublisher;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 从原终态事务读回 Job 事实,与状态一同提交;失败修正为成功保留为不同事实。 */
@Service
@RequiredArgsConstructor
public class JobTerminalOutboxService {
  private final JobInstanceMapper jobs;
  private final WorkflowRunMapper workflows;
  private final DomainEventPublisher publisher;

  @Transactional(propagation = Propagation.MANDATORY)
  public void writeTerminalEvent(String tenant, Long jobInstanceId) {
    JobInstanceEntity job = jobs.selectById(tenant, jobInstanceId);
    if (EmptyChecks.isNull(job))
      throw new IllegalStateException("terminal Job disappeared before outbox persistence");
    if (!JobInstanceStatus.terminalCodes().contains(job.getInstanceStatus())) return;
    boolean terminalJobSnapshotIsIncomplete = EmptyChecks.isNull(job.getFinishedAt())
        || EmptyChecks.isNull(job.getBizDate())
        || EmptyChecks.isNull(job.getVersion());
    if (terminalJobSnapshotIsIncomplete) {
      throw new IllegalStateException("terminal Job snapshot is incomplete");
    }
    WorkflowRunEntity owner = workflows.selectByNodeJobInstanceId(tenant, jobInstanceId);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("schemaVersion", "v1");
    payload.put("entityType", "JOB_INSTANCE");
    payload.put("tenantId", tenant);
    payload.put("jobInstanceId", job.getId());
    payload.put("jobCode", job.getJobCode());
    payload.put("operatorId", job.getOperatorId());
    payload.put("jobDefinitionId", job.getJobDefinitionId());
    payload.put("jobDefinitionVersion", job.getJobDefinitionVersion());
    payload.put("runAttempt", job.getRunAttempt());
    payload.put("terminalVersion", job.getVersion());
    payload.put("instanceStatus", job.getInstanceStatus());
    payload.put("dryRun", Boolean.TRUE.equals(job.getDryRun()));
    payload.put("bizDate", job.getBizDate().toString());
    payload.put("finishedAt", job.getFinishedAt().toString());
    payload.put("workflowRunId", EmptyChecks.isNull(owner) ? null : owner.getId());
    payload.put("traceId", job.getTraceId());
    DomainEvent.Builder eventBuilder = DomainEvent.builder(tenant);
    eventBuilder.aggregate("JOB_INSTANCE", jobInstanceId);
    eventBuilder.type("JOB_TERMINAL");
    String terminalStatus = job.getInstanceStatus();
    String eventKey = OutboxEventKeyGenerator.forJobTerminal(tenant, jobInstanceId, terminalStatus);
    eventBuilder.key(eventKey);
    eventBuilder.payload(payload);
    String eventTraceId = job.getTraceId();
    eventBuilder.traceId(eventTraceId);
    DomainEvent terminalEvent = eventBuilder.build();
    publisher.publish(terminalEvent);
  }
}
