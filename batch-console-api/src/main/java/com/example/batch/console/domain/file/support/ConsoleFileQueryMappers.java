package com.example.batch.console.domain.file.support;

import com.example.batch.console.domain.file.mapper.FileArrivalGroupMapper;
import com.example.batch.console.domain.file.mapper.FileChannelConfigMapper;
import com.example.batch.console.domain.file.mapper.FileDispatchRecordMapper;
import com.example.batch.console.domain.file.mapper.FileErrorRecordMapper;
import com.example.batch.console.domain.file.mapper.FilePipelineMapper;
import com.example.batch.console.domain.file.mapper.FilePipelineStepRunMapper;
import com.example.batch.console.domain.file.mapper.FileRecordMapper;
import com.example.batch.console.domain.file.mapper.FileTemplateConfigMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ConsoleFileQueryMappers {

  public final FileRecordMapper fileRecordMapper;
  public final FileArrivalGroupMapper fileArrivalGroupMapper;
  public final FileErrorRecordMapper fileErrorRecordMapper;
  public final FilePipelineMapper filePipelineMapper;
  public final FilePipelineStepRunMapper filePipelineStepRunMapper;
  public final FileDispatchRecordMapper fileDispatchRecordMapper;
  public final FileChannelConfigMapper fileChannelConfigMapper;
  public final FileTemplateConfigMapper fileTemplateConfigMapper;
}
