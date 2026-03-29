package com.example.batch.orchestrator.config.governance;

import com.example.batch.orchestrator.config.BatchMqTopicsProperties;
import com.example.batch.orchestrator.config.FileGovernanceProperties;
import com.example.batch.orchestrator.config.OutboxProperties;
import com.example.batch.orchestrator.config.PartitionLeaseProperties;
import com.example.batch.orchestrator.config.RateLimitProperties;
import com.example.batch.orchestrator.config.ResourceSchedulerProperties;
import com.example.batch.orchestrator.config.RetryGovernanceProperties;
import com.example.batch.orchestrator.config.SlaGovernanceProperties;
import com.example.batch.orchestrator.config.WorkerDrainProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration composition for orchestrator governance.
 *
 * <p>方案 A：不迁移 YAML key，只在代码层把分散的 {@code @ConfigurationProperties}
 * 聚合到一棵复合配置树，统一命名/层次/边界，减少重复注入点。</p>
 */
@Component
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

    public BatchOrchestratorGovernanceProperties(OutboxProperties outbox,
                                                 ResourceSchedulerProperties resourceScheduler,
                                                 RateLimitProperties rateLimit,
                                                 SlaGovernanceProperties sla,
                                                 RetryGovernanceProperties retry,
                                                 PartitionLeaseProperties partitionLease,
                                                 WorkerDrainProperties workerDrain,
                                                 FileGovernanceProperties fileGovernance,
                                                 BatchMqTopicsProperties mqTopics) {
        this.outbox = outbox;
        this.resourceScheduler = resourceScheduler;
        this.rateLimit = rateLimit;
        this.sla = sla;
        this.retry = retry;
        this.partitionLease = partitionLease;
        this.workerDrain = workerDrain;
        this.fileGovernance = fileGovernance;
        this.mqTopics = mqTopics;
    }

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
}

