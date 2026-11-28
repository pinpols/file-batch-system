package io.github.pinpols.batch.orchestrator.application.service.task;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobStepInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import io.github.pinpols.batch.orchestrator.mapper.TriggerRequestMapper;

@Component
@RequiredArgsConstructor
public class OrchestratorJobMappers {

    public final JobInstanceMapper jobInstanceMapper;
    public final JobPartitionMapper jobPartitionMapper;
    public final JobTaskMapper jobTaskMapper;
    public final JobStepInstanceMapper jobStepInstanceMapper;
    public final TriggerRequestMapper triggerRequestMapper;
}
