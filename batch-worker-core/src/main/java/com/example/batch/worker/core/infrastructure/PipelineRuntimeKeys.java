package com.example.batch.worker.core.infrastructure;

import com.example.batch.common.context.RunModeSupport;

/**
 * Worker 侧 pipeline 执行的共享运行时属性键。
 *
 * <p>请保持这些键与 {@code docs/architecture/core-model.md} 一致， 避免为相同的运行时概念引入模块内的同义别名。
 */
public final class PipelineRuntimeKeys {

  public static final String TASK_ID = "taskId";
  public static final String TRACE_ID = "traceId";
  public static final String RUN_MODE = RunModeSupport.RUN_MODE;

  /** 为兼容旧版 payload map 保留的历史别名。 */
  public static final String LEGACY_RUN_MODE = RunModeSupport.LEGACY_RUN_MODE;

  public static final String JOB_CODE = "jobCode";

  /** 为兼容旧版 worker 上下文保留的历史别名。 */
  public static final String PIPELINE_CODE = JOB_CODE;

  public static final String PIPELINE_DEFINITION_ID = "pipelineDefinitionId";
  public static final String PIPELINE_INSTANCE_ID = "pipelineInstanceId";
  public static final String PIPELINE_STEP_RUN_ID = "pipelineStepRunId";
  public static final String PIPELINE_STEP_DEFINITIONS = "pipelineStepDefinitions";
  public static final String PIPELINE_LAST_SUCCESS_STAGE = "pipelineLastSuccessStage";
  public static final String PIPELINE_CURRENT_STEP_CODE = "pipelineCurrentStepCode";
  public static final String PIPELINE_CURRENT_STAGE_CODE = "pipelineCurrentStageCode";
  public static final String PIPELINE_CURRENT_STEP_IMPL_CODE = "pipelineCurrentStepImplCode";
  public static final String PIPELINE_CURRENT_STEP_PARAMS = "pipelineCurrentStepParams";
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

  /**
   * PREPROCESS 超过堆安全阈值时不 decode 成 String，而是把原始字节 spool 到该临时文件。 PARSE 阶段通过 {@code
   * InputStreamReader(FileInputStream, charset)} 流式按行消费， 避免一次性把 byte[] 转 UTF-16 String 产生 1.5-2x
   * 内存放大。
   */
  public static final String IMPORT_LARGE_TEXT_PATH = "importLargeTextPath";

  /** 配合 {@link #IMPORT_LARGE_TEXT_PATH}：spool 文件的原始字符集（Charset 对象）。 */
  public static final String IMPORT_LARGE_TEXT_CHARSET = "importLargeTextCharset";

  /** 导出快照：snapshotMode、snapshotTs、sourcePartitions（可 JSON 序列化的 Map） */
  public static final String EXPORT_SNAPSHOT = "exportSnapshot";

  /**
   * 增量执行模式的水位起点。orchestrator 派发时通过 TaskDispatchMessage 透传过来, worker 业务逻辑通过 attributes 读取拼
   * SQL。FULL/CDC 模式下不存在或为 null。
   */
  public static final String HIGH_WATER_MARK_IN = "highWaterMarkIn";

  /**
   * 增量执行模式下 worker 上报的新水位高点。worker 业务逻辑写入 attributes, DefaultTaskExecutionWrapper 从 attributes
   * 读出后填进 TaskExecutionReport, orchestrator 在成功路径回写 {@code job_instance.high_water_mark_out}。
   */
  public static final String HIGH_WATER_MARK_OUT = "highWaterMarkOut";

  /**
   * ADR-009 Stage 1.2: worker 上报的节点产出 Map(key=业务字段名, value=JSON 原生类型)。各 worker adapter 在
   * buildSuccessResponse 时收集 attributes 中的关键产出键(如 fileId/recordCount/objectName/receiptCode)填入此
   * map;DefaultTaskExecutionWrapper 提取后透传给 orchestrator,持久化到 workflow_node_run.output JSONB, 供下游
   * workflow 节点 $.nodes.&lt;X&gt;.output.&lt;key&gt; DSL 引用。
   */
  public static final String NODE_OUTPUTS = "nodeOutputs";

  /**
   * 当前 task 的 1-based partition 序号(读自 EffectiveTaskConfig.partitionNo,源头 {@code
   * job_partition.partition_no})。Worker step 按 {@code rowIndex % PARTITION_COUNT == PARTITION_NO -
   * 1} 之类规则切数据时使用。{@code shard_strategy=NONE} 时为 1。
   */
  public static final String PARTITION_NO = "partitionNo";

  /**
   * 本次 job_instance 的 partition 总数(读自 EffectiveTaskConfig.partitionCount,源头 {@code
   * job_instance.expected_partition_count})。Worker 切分时与 {@link #PARTITION_NO} 配合;{@code count <= 1}
   * 时不分片。
   */
  public static final String PARTITION_COUNT = "partitionCount";

  /**
   * partition 业务标识(读自 EffectiveTaskConfig.partitionKey,源头 {@code job_partition.partition_key})。
   * 默认格式 {@code jobCode:bizDate:partitionNo};业务可在 plan-build 阶段覆盖为机构号 / hash 桶 / 数据范围等,worker step
   * 按业务字段切分时读它。
   */
  public static final String PARTITION_KEY = "partitionKey";

  private PipelineRuntimeKeys() {}
}
