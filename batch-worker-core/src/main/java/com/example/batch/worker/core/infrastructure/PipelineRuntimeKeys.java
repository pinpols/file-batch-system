package com.example.batch.worker.core.infrastructure;

import com.example.batch.common.context.RunModeSupport;

/**
 * Worker 侧 pipeline 执行的共享运行时属性键。
 *
 * <p>请保持这些键与 {@code docs/architecture/core-model.md} 一致，
 * 避免为相同的运行时概念引入模块内的同义别名。
 */
public final class PipelineRuntimeKeys {

    public static final String TASK_ID = "taskId";
    public static final String TRACE_ID = "traceId";
    public static final String RUN_MODE = RunModeSupport.RUN_MODE;
    /**
     * 为兼容旧版 payload map 保留的历史别名。
     */
    public static final String LEGACY_RUN_MODE = RunModeSupport.LEGACY_RUN_MODE;
    public static final String JOB_CODE = "jobCode";
    /**
     * 为兼容旧版 worker 上下文保留的历史别名。
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
