package io.github.pinpols.batch.console.domain.job.support;

import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobExecutionLogMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobInstanceMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobPartitionMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobStepInstanceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ConsoleJobQueryMappers {

  public final JobDefinitionMapper jobDefinitionMapper;
  public final JobInstanceMapper jobInstanceMapper;
  public final JobStepInstanceMapper jobStepInstanceMapper;
  public final JobPartitionMapper jobPartitionMapper;
  public final JobExecutionLogMapper jobExecutionLogMapper;
}
