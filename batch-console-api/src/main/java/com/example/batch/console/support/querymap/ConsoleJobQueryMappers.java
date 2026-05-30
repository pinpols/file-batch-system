package com.example.batch.console.support.querymap;

import com.example.batch.console.mapper.JobDefinitionMapper;
import com.example.batch.console.mapper.JobExecutionLogMapper;
import com.example.batch.console.mapper.JobInstanceMapper;
import com.example.batch.console.mapper.JobPartitionMapper;
import com.example.batch.console.mapper.JobStepInstanceMapper;
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
