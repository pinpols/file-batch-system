package com.example.batch.worker.core.infrastructure;

import com.example.batch.common.context.RunModeSupport;

/**
 * Shared runtime attribute keys for worker-side pipeline execution.
 *
 * <p>Keep these keys aligned with {@code docs/architecture/core-model.md} and
 * avoid introducing module-local synonyms for the same runtime concept.
 */
public final class PipelineRuntimeKeys {

    public static final String TASK_ID = "taskId";
    public static final String TRACE_ID = "traceId";
    public static final String RUN_MODE = RunModeSupport.RUN_MODE;
    /**
     * Legacy alias kept for compatibility with older payload maps.
     */
    public static final String LEGACY_RUN_MODE = RunModeSupport.LEGACY_RUN_MODE;
    public static final String JOB_CODE = "jobCode";
    /**
     * Legacy alias kept for compatibility with older worker contexts.
     */
    public static final String PIPELINE_CODE = JOB_CODE;
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
    public static final String PARSED_RECORDS_PATH = "parsedRecordsPath";
    public static final String VALIDATED_RECORDS_PATH = "validatedRecordsPath";
    public static final String GENERATED_FILE_PATH = "generatedFilePath";
    /** PREPROCESS 后的原始文件字节（如 Excel .xlsx），文本转换会破坏二进制内容时使用 */
    public static final String IMPORT_BINARY_PAYLOAD = "importBinaryPayload";
    /** 导出快照：snapshotMode、snapshotTs、sourcePartitions（可 JSON 序列化的 Map） */
    public static final String EXPORT_SNAPSHOT = "exportSnapshot";

    private PipelineRuntimeKeys() {
    }
}
