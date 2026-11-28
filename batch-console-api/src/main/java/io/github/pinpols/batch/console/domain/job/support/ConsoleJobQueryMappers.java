package io.github.pinpols.batch.console.domain.job.support;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobExecutionLogMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobInstanceMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobPartitionMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobStepInstanceMapper;

@Component
@RequiredArgsConstructor
public class ConsoleJobQueryMappers {

    public final JobDefinitionMapper jobDefinitionMapper;
    public final JobInstanceMapper jobInstanceMapper;
    public final JobStepInstanceMapper jobStepInstanceMapper;
    public final JobPartitionMapper jobPartitionMapper;
    public final JobExecutionLogMapper jobExecutionLogMapper;
}
