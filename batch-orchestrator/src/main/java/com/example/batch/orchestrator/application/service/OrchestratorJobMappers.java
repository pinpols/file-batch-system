package com.example.batch.orchestrator.application.service;

import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import com.example.batch.orchestrator.mapper.JobStepInstanceMapper;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
import com.example.batch.orchestrator.mapper.TriggerRequestMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OrchestratorJobMappers {

    public final JobInstanceMapper jobInstanceMapper;
    public final JobPartitionMapper jobPartitionMapper;
    public final JobTaskMapper jobTaskMapper;
    public final JobStepInstanceMapper jobStepInstanceMapper;
    public final TriggerRequestMapper triggerRequestMapper;
}
