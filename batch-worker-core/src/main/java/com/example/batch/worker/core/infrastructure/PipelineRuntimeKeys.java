package com.example.batch.worker.core.infrastructure;

public final class PipelineRuntimeKeys {

    public static final String TASK_ID = "taskId";
    public static final String TRACE_ID = "traceId";
    public static final String PIPELINE_CODE = "pipelineCode";
    public static final String PIPELINE_DEFINITION_ID = "pipelineDefinitionId";
    public static final String PIPELINE_INSTANCE_ID = "pipelineInstanceId";
    public static final String PIPELINE_STEP_RUN_ID = "pipelineStepRunId";
    public static final String PIPELINE_STEP_DEFINITIONS = "pipelineStepDefinitions";
    public static final String PIPELINE_LAST_SUCCESS_STAGE = "pipelineLastSuccessStage";
    public static final String PIPELINE_NEXT_STEP_CODE = "pipelineNextStepCode";
    public static final String PIPELINE_NEXT_STAGE_CODE = "pipelineNextStageCode";
    public static final String JOB_INSTANCE_ID = "jobInstanceId";
    public static final String FILE_ID = "fileId";
    public static final String FILE_RECORD = "fileRecord";
    public static final String TEMPLATE_CONFIG = "templateConfig";
    public static final String CHANNEL_CONFIG = "channelConfig";

    private PipelineRuntimeKeys() {
    }
}
