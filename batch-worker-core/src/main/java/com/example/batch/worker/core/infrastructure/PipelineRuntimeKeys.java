package com.example.batch.worker.core.infrastructure;

public final class PipelineRuntimeKeys {

    public static final String TASK_ID = "taskId";
    public static final String TRACE_ID = "traceId";
    public static final String PIPELINE_CODE = "pipelineCode";
    public static final String PIPELINE_INSTANCE_ID = "pipelineInstanceId";
    public static final String PIPELINE_LAST_SUCCESS_STAGE = "pipelineLastSuccessStage";
    public static final String JOB_INSTANCE_ID = "jobInstanceId";
    public static final String FILE_ID = "fileId";
    public static final String FILE_RECORD = "fileRecord";
    public static final String TEMPLATE_CONFIG = "templateConfig";
    public static final String CHANNEL_CONFIG = "channelConfig";

    private PipelineRuntimeKeys() {
    }
}
