package com.example.batch.orchestrator.config.governance;

import com.example.batch.orchestrator.config.BatchMqTopicsProperties;
import com.example.batch.orchestrator.config.FileGovernanceProperties;
import com.example.batch.orchestrator.config.MqRoutingProperties;
import com.example.batch.orchestrator.config.OutboxProperties;
import com.example.batch.orchestrator.config.PartitionLeaseProperties;
import com.example.batch.orchestrator.config.RateLimitProperties;
import com.example.batch.orchestrator.config.ResourceSchedulerProperties;
import com.example.batch.orchestrator.config.RetryGovernanceProperties;
import com.example.batch.orchestrator.config.SlaGovernanceProperties;
import com.example.batch.orchestrator.config.WorkerDrainProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Orchestrator 治理配置聚合类，将分散的 {@code @ConfigurationProperties} 统一组合，减少重复注入点。 */
@Component
@RequiredArgsConstructor
public class BatchOrchestratorGovernanceProperties {

  private final OutboxProperties outbox;
  private final ResourceSchedulerProperties resourceScheduler;
  private final RateLimitProperties rateLimit;
  private final SlaGovernanceProperties sla;
  private final RetryGovernanceProperties retry;
  private final PartitionLeaseProperties partitionLease;
  private final WorkerDrainProperties workerDrain;
  private final FileGovernanceProperties fileGovernance;
  private final BatchMqTopicsProperties mqTopics;
  private final MqRoutingProperties mqRouting;

  public OutboxProperties outbox() {
    return outbox;
  }

  public ResourceSchedulerProperties resourceScheduler() {
    return resourceScheduler;
  }

  public RateLimitProperties rateLimit() {
    return rateLimit;
  }

  public SlaGovernanceProperties sla() {
    return sla;
  }

  public RetryGovernanceProperties retry() {
    return retry;
  }

  public PartitionLeaseProperties partitionLease() {
    return partitionLease;
  }

  public WorkerDrainProperties workerDrain() {
    return workerDrain;
  }

  public FileGovernanceProperties fileGovernance() {
    return fileGovernance;
  }

  public BatchMqTopicsProperties mqTopics() {
    return mqTopics;
  }

  public MqRoutingProperties mqRouting() {
    return mqRouting;
  }
}
