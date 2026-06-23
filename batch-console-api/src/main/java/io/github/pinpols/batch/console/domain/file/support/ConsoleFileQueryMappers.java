package io.github.pinpols.batch.console.domain.file.support;

import io.github.pinpols.batch.console.domain.file.mapper.FileArrivalGroupMapper;
import io.github.pinpols.batch.console.domain.file.mapper.FileChannelConfigMapper;
import io.github.pinpols.batch.console.domain.file.mapper.FileDispatchRecordMapper;
import io.github.pinpols.batch.console.domain.file.mapper.FileErrorRecordMapper;
import io.github.pinpols.batch.console.domain.file.mapper.FilePipelineMapper;
import io.github.pinpols.batch.console.domain.file.mapper.FilePipelineStepRunMapper;
import io.github.pinpols.batch.console.domain.file.mapper.FileRecordMapper;
import io.github.pinpols.batch.console.domain.file.mapper.FileTemplateConfigMapper;
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
