package io.github.pinpols.batch.worker.core.infrastructure;

import io.github.pinpols.batch.common.context.RunModeSupport;

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

  /**
   * ADR-026 dry-run 演练标记。orchestrator 在 task payload 里塞 {@code dryRun=true}，worker SDK 抽到
   * attributes， step plugin 通过 {@link
   * io.github.pinpols.batch.common.service.DryRunGuard#fromAttributes} 拉取 guard 包裹副作用。
   */
  public static final String DRY_RUN = "dryRun";

  public static final String FILE_ID = "fileId";
  public static final String FILE_RECORD = "fileRecord";
  public static final String TEMPLATE_CONFIG = "templateConfig";
  public static final String CHANNEL_CONFIG = "channelConfig";
  public static final String IMPORT_PAYLOAD = "importPayload";
  public static final String IMPORT_NORMALIZED_PAYLOAD = "normalizedPayload";
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

  public static final String IMPORT_SCHEMA_FIELDS = "schemaFields";
  public static final String IMPORT_TOTAL_COUNT = "totalCount";
  public static final String IMPORT_PARSED_COUNT = "parsedCount";
  public static final String IMPORT_VALIDATED_COUNT = "validatedCount";
  public static final String IMPORT_CUSTOMER_PAYLOAD_COUNT = "customerPayloadCount";
  public static final String IMPORT_LOADED_COUNT = "loadedCount";
  public static final String IMPORT_SKIPPED_COUNT = "skippedCount";
  public static final String IMPORT_SKIP_THRESHOLD_EXCEEDED = "skipThresholdExceeded";

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
   * V94: data_interval 半开区间起点 (Airflow 风格 {@code Instant}). 触发侧已计算 (CRON 取本次 fireAt, FIXED_RATE 同)
   * 或 API 调用方显式提供. 业务可拼 SQL {@code WHERE update_time >= :dataIntervalStart}. null 时表示退化为 bizDate 单点
   * 模式, 业务侧自行用 bizDate.atStartOfDay 回退.
   */
  public static final String DATA_INTERVAL_START = "dataIntervalStart";

  /**
   * V94: data_interval 半开区间终点 (Airflow 风格 {@code Instant}). CRON 路径取 nextFireAt, FIXED_RATE 取
   * fireAt+interval. 业务可拼 SQL {@code WHERE update_time < :dataIntervalEnd}. null 时业务侧用 bizDate+1
   * 回退.
   */
  public static final String DATA_INTERVAL_END = "dataIntervalEnd";

  /**
   * ADR-009 Stage 1.2: worker 上报的节点产出 Map(key=业务字段名, value=JSON 原生类型)。各 worker adapter 在
   * buildSuccessResponse 时收集 attributes 中的关键产出键(如 fileId/recordCount/objectName/receiptCode)填入此
   * map;DefaultTaskExecutionWrapper 提取后透传给 orchestrator,持久化到 workflow_node_run.output JSONB, 供下游
   * workflow 节点 $.nodes.&lt;X&gt;.output.&lt;key&gt; DSL 引用。
   */
  public static final String NODE_OUTPUTS = "nodeOutputs";

  /**
   * ADR-030 §C: worker pipeline 成功路径运行 {@link
   * io.github.pinpols.batch.common.verifier.ContentVerifierRegistry} 后，把失败的 verifier 结果列表写入
   * attributes 这一键；{@code DefaultTaskExecutionWrapper.buildReport} 提取后透传给 orchestrator （后续 PR 由
   * orchestrator 写入 outbox 走告警面板）。null/empty 等价"全部通过"。
   *
   * <p>每个 List 元素 schema：{@code {code, message, evidence}}，与 {@link
   * io.github.pinpols.batch.common.verifier.VerifyResult} 一一对应。
   */
  public static final String VERIFIER_FAILURES = "verifierFailures";

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
   * IMPORT range-slice 标记:PREPROCESS 已按 {@link #PARTITION_NO}/{@link #PARTITION_COUNT} 用对象存储 range
   * GET(offset/length)只下载本片字节(行边界对齐),spool 文件已只含本片记录。 置 {@code true} 时 PARSE 的 line-mod 过滤({@code
   * lineNo % count})必须跳过,否则二次切分丢数据。缺失/false 时维持整份下载 + line-mod 现状。
   */
  public static final String PARTITION_PRESLICED = "partitionPresliced";

  /**
   * partition 业务标识(读自 EffectiveTaskConfig.partitionKey,源头 {@code job_partition.partition_key})。
   * 默认格式 {@code jobCode:bizDate:partitionNo};业务可在 plan-build 阶段覆盖为机构号 / hash 桶 / 数据范围等,worker step
   * 按业务字段切分时读它。
   */
  public static final String PARTITION_KEY = "partitionKey";

  /** 分区计划契约版本；当前为 1，null 表示旧平台或非标准计划。 */
  public static final String PARTITION_PLAN_VERSION = "partitionPlanVersion";

  /** 当前分片 0-based 下标；与 {@link #SHARD_TOTAL} 配合给 worker/plugin 做稳定切分。 */
  public static final String SHARD_INDEX = "shardIndex";

  /** 本次分片总数；语义等同 partitionCount，但与 shardIndex 组成 0-based worker 契约。 */
  public static final String SHARD_TOTAL = "shardTotal";

  /** 半开范围起点；仅平台拿到 expectedRows/recordCount 等总量提示时存在。 */
  public static final String RANGE_START_INCLUSIVE = "rangeStartInclusive";

  /** 半开范围终点；仅平台拿到 expectedRows/recordCount 等总量提示时存在。 */
  public static final String RANGE_END_EXCLUSIVE = "rangeEndExclusive";

  /** 当前分片预期行数；无总量提示时不存在。 */
  public static final String EXPECTED_ROWS = "expectedRows";

  private PipelineRuntimeKeys() {}
}
