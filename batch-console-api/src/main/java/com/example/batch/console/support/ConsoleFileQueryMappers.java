package com.example.batch.console.support;

import com.example.batch.console.mapper.FileArrivalGroupMapper;
import com.example.batch.console.mapper.FileChannelConfigMapper;
import com.example.batch.console.mapper.FileDispatchRecordMapper;
import com.example.batch.console.mapper.FileErrorRecordMapper;
import com.example.batch.console.mapper.FilePipelineMapper;
import com.example.batch.console.mapper.FilePipelineStepRunMapper;
import com.example.batch.console.mapper.FileRecordMapper;
import com.example.batch.console.mapper.FileTemplateConfigMapper;
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
