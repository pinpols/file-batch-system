package io.github.pinpols.batch.console.infrastructure.excel;

import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.AUTH_TYPES;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.BATCH_WINDOW_SHEET;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.BUSINESS_CALENDAR_SHEET;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.CHANNEL_SHEET;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.CHANNEL_TYPES;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_AUTH_TYPE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_BIZ_TYPE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_CALENDAR_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_CHANNEL_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_CHANNEL_NAME;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_CHANNEL_TYPE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_CONDITION_EXPR;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_CONFIG_JSON;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_DEFAULT_PARAMS;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_DEPENDS_ON_JOB_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_DESCRIPTION;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_EDGE_TYPE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_ENABLED;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_EXECUTION_HANDLER;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_EXECUTION_MODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_FROM_NODE_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_JOB_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_JOB_NAME;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_JOB_TYPE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_NODE_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_NODE_NAME;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_NODE_ORDER;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_NODE_PARAMS;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_NODE_TYPE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_PARAM_SCHEMA;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_PIPELINE_NAME;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_PIPELINE_TYPE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_QUEUE_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_RECEIPT_POLICY;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_RELATED_JOB_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_RELATED_PIPELINE_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_RETRY_MAX_COUNT;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_RETRY_POLICY;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_SCHEDULE_EXPR;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_SCHEDULE_TYPE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_SHARD_STRATEGY;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_STAGE_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_STEP_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_STEP_NAME;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_TENANT_ID;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_TIMEOUT_SECONDS;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_TO_NODE_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_VERSION;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_WATERMARK_FIELD;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_WINDOW_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_WORKER_GROUP;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_WORKFLOW_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_WORKFLOW_NAME;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_WORKFLOW_TYPE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_WORKFLOW_VERSION;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.EDGE_TYPES;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.EXECUTION_MODES;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.FILE_TEMPLATE_SHEET;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.JOB_SHEET;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.JOB_TYPES;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.NODE_TYPES;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.PIPELINE_SHEET;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.PIPELINE_TYPES;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.RECEIPT_POLICIES;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.RESOURCE_QUEUE_SHEET;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.RETRY_POLICIES;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.SCHEDULE_TYPES;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.SHARD_STRATEGIES;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.STAGE_CODES;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.STEP_SHEET;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.WF_DEF_SHEET;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.WF_EDGE_SHEET;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.WF_NODE_SHEET;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.WORKFLOW_TYPES;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelWorkbookWriter.BATCH_WINDOW_COLUMNS;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelWorkbookWriter.BUSINESS_CALENDAR_COLUMNS;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelWorkbookWriter.CHANNEL_COLUMNS;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelWorkbookWriter.FILE_TEMPLATE_COLUMNS;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelWorkbookWriter.JOB_COLUMNS;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelWorkbookWriter.PIPELINE_COLUMNS;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelWorkbookWriter.RESOURCE_QUEUE_COLUMNS;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelWorkbookWriter.STEP_COLUMNS;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelWorkbookWriter.WF_DEF_COLUMNS;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelWorkbookWriter.WF_EDGE_COLUMNS;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelWorkbookWriter.WF_NODE_COLUMNS;
import static io.github.pinpols.batch.console.support.excel.ConsoleExcelStyles.addDropdownValidation;
import static io.github.pinpols.batch.console.support.excel.ConsoleExcelStyles.optionalColumn;
import static io.github.pinpols.batch.console.support.excel.ConsoleExcelStyles.requiredColumn;

import io.github.pinpols.batch.common.enums.BatchWindowEndStrategy;
import io.github.pinpols.batch.common.enums.CatchUpPolicyType;
import io.github.pinpols.batch.common.enums.DictEnum;
import io.github.pinpols.batch.common.enums.FileChecksumType;
import io.github.pinpols.batch.common.enums.FileCompressType;
import io.github.pinpols.batch.common.enums.FileEncryptType;
import io.github.pinpols.batch.common.enums.FileTemplateFormat;
import io.github.pinpols.batch.common.enums.FileTemplateType;
import io.github.pinpols.batch.common.enums.HolidayRollRule;
import io.github.pinpols.batch.common.enums.JobType;
import io.github.pinpols.batch.common.enums.OutOfWindowAction;
import io.github.pinpols.batch.common.enums.PipelineType;
import io.github.pinpols.batch.common.enums.QueuePriorityPolicy;
import io.github.pinpols.batch.common.enums.ResourceQueueType;
import io.github.pinpols.batch.common.enums.ScheduleType;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.support.excel.ConsoleExcelStyles.ColumnGuide;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import org.apache.poi.ss.usermodel.Sheet;
import org.springframework.context.MessageSource;

/**
 * 11 个配置包 sheet 的字段、顺序和业务约束的集中声明。
 *
 * <p>该类看起来行数较多是刻意的：它是配置包协议的“单一事实源”，不是把多个业务流程塞进一个服务。导入校验、模板生成和错误定位都从这里读取规则，
 * 集中维护可以避免同一个 sheet 在不同链路出现列顺序或必填语义漂移；新增 sheet 应优先新增规格，而不是在各个校验器里复制条件。
 */
final class ConfigPackageSheetSpecs {

  private static final String EMPTY = "";

  /**
   * 枚举下拉数组 — 单一权威源，全部从 enum 声明顺序 + ConfigPackageExcelValidator 集合派生。
   *
   * <p>下拉项展示顺序 = enum 声明顺序（业务认知顺序），不直接用 Set#toArray 的 unordered 输出。 CI 单测 {@code
   * ConfigPackageEnumDropdownConsistencyTest} 锁定这 4 个数组的内容 == validator 集合， 防止文档侧漂移（job_type 漏
   * PROCESS / schedule_type 多 EVENT/ONE_TIME / pipeline_type 漏 PROCESS / stage_code 含 TRANSFER
   * 等历史漂移问题已修复，CI 守护后续不再退步）。
   */
  static final String[] JOB_TYPE_DROPDOWN = DictEnum.codeList(JobType.class).toArray(String[]::new);

  static final String[] SCHEDULE_TYPE_DROPDOWN =
      DictEnum.codeList(ScheduleType.class).toArray(String[]::new);

  static final String[] PIPELINE_TYPE_DROPDOWN =
      DictEnum.codeList(PipelineType.class).toArray(String[]::new);

  /**
   * stage_code 在 worker 侧拆 3 enum（ImportStage / ExportStage / DispatchStage / ProcessStage 等模块自管），
   * 这里只取 validator 的 union 集合，并按业务流转顺序固定列出，避免 Set 转 array 出现非预期顺序。
   */
  static final String[] STAGE_CODE_DROPDOWN = {
    "RECEIVE",
    "PREPROCESS",
    "PARSE",
    "VALIDATE",
    "LOAD",
    "FEEDBACK",
    "PREPARE",
    "COMPUTE",
    "GENERATE",
    "STORE",
    "REGISTER",
    "COMPLETE",
    "COMMIT",
    "DISPATCH",
    "ACK",
    "RETRY",
    "COMPENSATE"
  };

  private static final String GUIDE_IMPORT = "IMPORT";
  private static final String GUIDE_TIMEOUT_DESC = "超时秒数。";
  private static final String GUIDE_DESC_DESC = "描述。";
  private static final String GUIDE_STR = "字符串";
  private static final String GUIDE_ENUM = "枚举";
  private static final String GUIDE_INT = "整数";
  private static final String GUIDE_BOOL = "布尔值";
  private static final String GUIDE_JSON = "JSON";
  private static final String GUIDE_SQL = "SQL";
  private static final String GUIDE_TRUE = "TRUE";
  private static final String GUIDE_FALSE = "FALSE";
  private static final String GUIDE_NONE = "NONE";
  private static final String GUIDE_ENABLED_DESC = "是否启用。";
  private static final String GUIDE_TENANT_DESC = "所属租户。";
  private static final String GUIDE_TENANT_EXAMPLE = "tenant-a";
  private static final String GUIDE_JOB_EXAMPLE = "JOB_IMPORT_CUSTOMER";
  private static final String GUIDE_EMPTY_JSON = "{}";
  private static final String GUIDE_VERSION_ONE = "1";

  // ── 真实非空示例片段（提取自 batch-e2e-tests import/export-template-config-seed.sql，
  //    与 worker 实际解析逻辑一致；精简但结构完整、可直接改）。
  //    import 与 export 两套 field_mappings / query_param_schema 结构不同，分别给。
  //    片段文案与 3 张只读说明 sheet 已外置到 classpath 资源 /config-package-guidance.json，
  //    由 ConfigPackageGuidanceContent 加载；此处常量名保留不变（测试与 FILL_EXAMPLE_OVERRIDE 仍引用）。

  private static final ConfigPackageGuidanceContent GUIDANCE = ConfigPackageGuidanceContent.load();

  /**
   * import 的 field_mappings：source/targetColumn/type/required/maxLength/format（非极简 source/target）。
   */
  private static final String EXAMPLE_IMPORT_FIELD_MAPPINGS =
      GUIDANCE.fragment("importFieldMappings");

  /** export 的 field_mappings：sourceColumn/header/format（numberFormat 走 format 如 #,##0.00）。 */
  private static final String EXAMPLE_EXPORT_FIELD_MAPPINGS =
      GUIDANCE.fragment("exportFieldMappings");

  /**
   * import 的
   * query_param_schema.jdbcMappedImport：schema/table/columnMappings/conflictColumns/systemBindings。
   */
  private static final String EXAMPLE_IMPORT_QUERY_PARAM_SCHEMA =
      GUIDANCE.fragment("importQueryParamSchema");

  /**
   * export 的 query_param_schema：JSON Schema 参数声明，或 sqlTemplateExport.cursorColumn /
   * jdbcMappedExport。
   */
  private static final String EXAMPLE_EXPORT_QUERY_PARAM_SCHEMA =
      GUIDANCE.fragment("exportQueryParamSchema");

  /** export 的 default_query_sql：命名参数 :tenantId/:batchNo，单条安全 SELECT。 */
  private static final String EXAMPLE_EXPORT_QUERY_SQL = GUIDANCE.fragment("exportQuerySql");

  /** validation_rule_set（import）：maxErrorRate/stopOnFirstError/duplicateKeyCheck。 */
  private static final String EXAMPLE_IMPORT_VALIDATION_RULE_SET =
      GUIDANCE.fragment("importValidationRuleSet");

  /** 渠道 config_json：endpoint + auth + credentials（DISPATCH 用）。 */
  private static final String EXAMPLE_CHANNEL_CONFIG_JSON = GUIDANCE.fragment("channelConfigJson");

  /** job default_params：IMPORT/EXPORT 用 templateCode 引用 file_template_config。 */
  private static final String EXAMPLE_JOB_DEFAULT_PARAMS = GUIDANCE.fragment("jobDefaultParams");

  /** PROCESS 步骤 step_params：targetSchema/targetTable/sql/columnMappings。 */
  private static final String EXAMPLE_PROCESS_STEP_PARAMS = GUIDANCE.fragment("processStepParams");

  /** DISPATCH 步骤 step_params：channelCode 指向 file_channel_config.channel_code。 */
  private static final String EXAMPLE_DISPATCH_STEP_PARAMS =
      GUIDANCE.fragment("dispatchStepParams");

  /**
   * 「填写示例」per-(sheet,column) 覆盖：仅最难字段给完整片段。值取自 e2e fixture（与 worker 解析逻辑一致）。
   * file_template_config 的 field_mappings / query_param_schema 同时给 import 和 export 两套结构。
   */
  static final Map<String, Map<String, String>> FILL_EXAMPLE_OVERRIDE = Map.ofEntries(
      Map.entry(
          FILE_TEMPLATE_SHEET,
          Map.ofEntries(
              Map.entry(
                  "field_mappings",
                  "IMPORT: "
                      + EXAMPLE_IMPORT_FIELD_MAPPINGS
                      + "\nEXPORT: "
                      + EXAMPLE_EXPORT_FIELD_MAPPINGS),
              Map.entry(
                  "query_param_schema",
                  "IMPORT: "
                      + EXAMPLE_IMPORT_QUERY_PARAM_SCHEMA
                      + "\nEXPORT: "
                      + EXAMPLE_EXPORT_QUERY_PARAM_SCHEMA),
              Map.entry("validation_rule_set", EXAMPLE_IMPORT_VALIDATION_RULE_SET),
              Map.entry("default_query_sql", EXAMPLE_EXPORT_QUERY_SQL))),
      Map.entry(CHANNEL_SHEET, Map.of(COL_CONFIG_JSON, EXAMPLE_CHANNEL_CONFIG_JSON)),
      Map.entry(JOB_SHEET, Map.of(COL_DEFAULT_PARAMS, EXAMPLE_JOB_DEFAULT_PARAMS)),
      Map.entry(
          STEP_SHEET,
          Map.of(
              "step_params",
              "PROCESS: "
                  + EXAMPLE_PROCESS_STEP_PARAMS
                  + "\nDISPATCH: "
                  + EXAMPLE_DISPATCH_STEP_PARAMS)));

  private static final Set<String> FILE_TEMPLATE_TYPES = DictEnum.codes(FileTemplateType.class);
  private static final Set<String> FILE_FORMAT_TYPES = DictEnum.codes(FileTemplateFormat.class);
  private static final Set<String> CHECKSUM_TYPES = DictEnum.codes(FileChecksumType.class);
  private static final Set<String> COMPRESS_TYPES = DictEnum.codes(FileCompressType.class);
  private static final Set<String> ENCRYPT_TYPES = DictEnum.codes(FileEncryptType.class);
  private static final Set<String> QUEUE_TYPES = DictEnum.codes(ResourceQueueType.class);
  private static final Set<String> PRIORITY_POLICIES = DictEnum.codes(QueuePriorityPolicy.class);
  private static final Set<String> HOLIDAY_ROLL_RULES = DictEnum.codes(HolidayRollRule.class);
  private static final Set<String> CATCH_UP_POLICIES = DictEnum.codes(CatchUpPolicyType.class);
  private static final Set<String> END_STRATEGIES = DictEnum.codes(BatchWindowEndStrategy.class);
  private static final Set<String> OUT_OF_WINDOW_ACTIONS = DictEnum.codes(OutOfWindowAction.class);
  private static final int[] FILE_TEMPLATE_BOOLEAN_COLUMNS = {8, 27, 31, 32, 33, 34, 36, 38};

  private final MessageSource messageSource;

  ConfigPackageSheetSpecs(MessageSource messageSource) {
    this.messageSource = messageSource;
  }

  record SheetDef(
      String name,
      List<String> columns,
      Map<String, ColumnGuide> guides,
      BiConsumer<Sheet, Locale> validationApplier) {}

  List<SheetDef> build() {
    return List.of(
        new SheetDef(
            RESOURCE_QUEUE_SHEET,
            RESOURCE_QUEUE_COLUMNS,
            buildResourceQueueGuides(),
            this::applyResourceQueueValidations),
        new SheetDef(
            BUSINESS_CALENDAR_SHEET,
            BUSINESS_CALENDAR_COLUMNS,
            buildBusinessCalendarGuides(),
            this::applyBusinessCalendarValidations),
        new SheetDef(
            BATCH_WINDOW_SHEET,
            BATCH_WINDOW_COLUMNS,
            buildBatchWindowGuides(),
            this::applyBatchWindowValidations),
        new SheetDef(JOB_SHEET, JOB_COLUMNS, buildJobGuides(), this::applyJobValidations),
        new SheetDef(
            CHANNEL_SHEET, CHANNEL_COLUMNS, buildChannelGuides(), this::applyChannelValidations),
        new SheetDef(
            FILE_TEMPLATE_SHEET,
            FILE_TEMPLATE_COLUMNS,
            buildFileTemplateGuides(),
            this::applyFileTemplateValidations),
        new SheetDef(
            PIPELINE_SHEET,
            PIPELINE_COLUMNS,
            buildPipelineGuides(),
            this::applyPipelineValidations),
        new SheetDef(STEP_SHEET, STEP_COLUMNS, buildStepGuides(), this::applyStepValidations),
        new SheetDef(WF_DEF_SHEET, WF_DEF_COLUMNS, buildWfDefGuides(), this::applyWfDefValidations),
        new SheetDef(
            WF_NODE_SHEET, WF_NODE_COLUMNS, buildWfNodeGuides(), this::applyWfNodeValidations),
        new SheetDef(
            WF_EDGE_SHEET, WF_EDGE_COLUMNS, buildWfEdgeGuides(), this::applyWfEdgeValidations));
  }

  private Map<String, ColumnGuide> buildResourceQueueGuides() {
    return Map.ofEntries(
        Map.entry(
            COL_TENANT_ID, optionalColumn(GUIDE_TENANT_DESC, GUIDE_STR, GUIDE_TENANT_EXAMPLE)),
        Map.entry("queue_code", requiredColumn("资源队列编码。", GUIDE_STR, "import-queue")),
        Map.entry("queue_name", requiredColumn("资源队列名称。", GUIDE_STR, "导入主队列")),
        Map.entry(
            "queue_type",
            requiredColumn(
                "队列类型。", GUIDE_ENUM, GUIDE_IMPORT, GUIDE_IMPORT, "EXPORT", "DISPATCH", "MIXED")),
        Map.entry("max_running_jobs", requiredColumn("最大并发作业数。", GUIDE_INT, "10")),
        Map.entry("max_running_partitions", requiredColumn("最大并发分区数。", GUIDE_INT, "20")),
        Map.entry("max_qps", requiredColumn("最大派发 QPS。", GUIDE_INT, "100")),
        Map.entry(COL_WORKER_GROUP, optionalColumn("Worker 分组。", GUIDE_STR, "import")),
        Map.entry("resource_tag", optionalColumn("资源标签。", GUIDE_STR, "standard")),
        Map.entry(
            "priority_policy",
            optionalColumn("优先级策略。", GUIDE_ENUM, "FIFO", "FIFO", "PRIORITY", "FAIR_SHARE")),
        Map.entry("fair_share_weight", requiredColumn("公平调度权重。", GUIDE_INT, "1")),
        Map.entry(
            COL_ENABLED,
            optionalColumn(GUIDE_ENABLED_DESC, GUIDE_BOOL, GUIDE_TRUE, GUIDE_TRUE, GUIDE_FALSE)),
        Map.entry(COL_DESCRIPTION, optionalColumn(GUIDE_DESC_DESC, GUIDE_STR, "导入任务默认资源队列")));
  }

  private Map<String, ColumnGuide> buildBusinessCalendarGuides() {
    return Map.ofEntries(
        Map.entry(
            COL_TENANT_ID, optionalColumn(GUIDE_TENANT_DESC, GUIDE_STR, GUIDE_TENANT_EXAMPLE)),
        Map.entry(
            ConfigPackageExcelSchema.BusinessCalendar.COL_CALENDAR_CODE,
            requiredColumn("业务日历编码。", GUIDE_STR, "default-calendar")),
        Map.entry(
            ConfigPackageExcelSchema.BusinessCalendar.COL_CALENDAR_NAME,
            requiredColumn("业务日历名称。", GUIDE_STR, "默认业务日历")),
        Map.entry(
            ConfigPackageExcelSchema.BusinessCalendar.COL_TIMEZONE,
            requiredColumn("时区 ID。", GUIDE_STR, "Asia/Shanghai")),
        Map.entry(
            ConfigPackageExcelSchema.BusinessCalendar.COL_HOLIDAY_ROLL_RULE,
            optionalColumn("节假日顺延规则。", GUIDE_ENUM, "SKIP", "SKIP", "NEXT_WORKDAY", "PREV_WORKDAY")),
        Map.entry(
            ConfigPackageExcelSchema.BusinessCalendar.COL_CATCH_UP_POLICY,
            optionalColumn("补跑策略。", GUIDE_ENUM, GUIDE_NONE, GUIDE_NONE, "AUTO", "MANUAL_APPROVAL")),
        Map.entry(
            ConfigPackageExcelSchema.BusinessCalendar.COL_CATCH_UP_MAX_DAYS,
            requiredColumn("最大补跑天数。", GUIDE_INT, "0")),
        Map.entry(
            ConfigPackageExcelSchema.BusinessCalendar.COL_HOLIDAYS,
            optionalColumn("节假日，逗号分隔 yyyy-MM-dd。", GUIDE_STR, "2026-01-01,2026-10-01")),
        Map.entry(
            COL_ENABLED,
            optionalColumn(GUIDE_ENABLED_DESC, GUIDE_BOOL, GUIDE_TRUE, GUIDE_TRUE, GUIDE_FALSE)),
        Map.entry(COL_DESCRIPTION, optionalColumn(GUIDE_DESC_DESC, GUIDE_STR, "默认业务日历")));
  }

  private Map<String, ColumnGuide> buildBatchWindowGuides() {
    return Map.ofEntries(
        Map.entry(
            COL_TENANT_ID, optionalColumn(GUIDE_TENANT_DESC, GUIDE_STR, GUIDE_TENANT_EXAMPLE)),
        Map.entry("window_code", requiredColumn("批次窗口编码。", GUIDE_STR, "always-open")),
        Map.entry("window_name", requiredColumn("批次窗口名称。", GUIDE_STR, "全天窗口")),
        Map.entry("timezone", requiredColumn("时区 ID。", GUIDE_STR, "Asia/Shanghai")),
        Map.entry("start_time", requiredColumn("开始时间 HH:mm 或 HH:mm:ss。", "时间", "00:00")),
        Map.entry("end_time", requiredColumn("结束时间 HH:mm 或 HH:mm:ss。", "时间", "23:59")),
        Map.entry(
            "end_strategy",
            optionalColumn(
                "窗口结束策略。", GUIDE_ENUM, "FINISH_RUNNING", "STOP", "FINISH_RUNNING", "CONTINUE")),
        Map.entry(
            "out_of_window_action", optionalColumn("窗口外动作。", GUIDE_ENUM, "WAIT", "WAIT", "FAIL")),
        Map.entry(
            "allow_cross_day",
            optionalColumn("是否允许跨日。", GUIDE_BOOL, GUIDE_FALSE, GUIDE_TRUE, GUIDE_FALSE)),
        Map.entry(
            COL_ENABLED,
            optionalColumn(GUIDE_ENABLED_DESC, GUIDE_BOOL, GUIDE_TRUE, GUIDE_TRUE, GUIDE_FALSE)),
        Map.entry(COL_DESCRIPTION, optionalColumn(GUIDE_DESC_DESC, GUIDE_STR, "默认批次窗口")));
  }

  private void applyJobValidations(Sheet sheet, Locale locale) {
    addDropdownValidation(
        sheet,
        3,
        JOB_TYPES.toArray(String[]::new),
        "excel.job.def.job_type.prompt_title",
        "excel.job.def.job_type.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        sheet,
        7,
        SCHEDULE_TYPES.toArray(String[]::new),
        "excel.job.def.schedule_type.prompt_title",
        "excel.job.def.schedule_type.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        sheet,
        11,
        RETRY_POLICIES.toArray(String[]::new),
        "excel.job.def.retry_policy.prompt_title",
        "excel.job.def.retry_policy.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        sheet,
        14,
        SHARD_STRATEGIES.toArray(String[]::new),
        "excel.job.def.shard_strategy.prompt_title",
        "excel.job.def.shard_strategy.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        sheet,
        15,
        EXECUTION_MODES.toArray(String[]::new),
        "excel.job.def.execution_mode.prompt_title",
        "excel.job.def.execution_mode.prompt_box",
        messageSource,
        locale);
    boolDropdown(sheet, 20, locale);
  }

  private void applyChannelValidations(Sheet sheet, Locale locale) {
    addDropdownValidation(
        sheet,
        3,
        CHANNEL_TYPES.toArray(String[]::new),
        "excel.channel.channel_type.prompt_title",
        "excel.channel.channel_type.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        sheet,
        5,
        AUTH_TYPES.toArray(String[]::new),
        "excel.channel.auth_type.prompt_title",
        "excel.channel.auth_type.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        sheet,
        7,
        RECEIPT_POLICIES.toArray(String[]::new),
        "excel.channel.receipt_policy.prompt_title",
        "excel.channel.receipt_policy.prompt_box",
        messageSource,
        locale);
    boolDropdown(sheet, 9, locale);
  }

  private void applyResourceQueueValidations(Sheet sheet, Locale locale) {
    addDropdownValidation(
        sheet,
        3,
        QUEUE_TYPES.toArray(String[]::new),
        "excel.queue.queue_type.prompt_title",
        "excel.queue.queue_type.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        sheet,
        9,
        PRIORITY_POLICIES.toArray(String[]::new),
        "excel.queue.priority_policy.prompt_title",
        "excel.queue.priority_policy.prompt_box",
        messageSource,
        locale);
    boolDropdown(sheet, 11, locale);
  }

  private void applyBusinessCalendarValidations(Sheet sheet, Locale locale) {
    addDropdownValidation(
        sheet,
        4,
        HOLIDAY_ROLL_RULES.toArray(String[]::new),
        "excel.calendar.holiday_roll_rule.prompt_title",
        "excel.calendar.holiday_roll_rule.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        sheet,
        5,
        CATCH_UP_POLICIES.toArray(String[]::new),
        "excel.calendar.catch_up_policy.prompt_title",
        "excel.calendar.catch_up_policy.prompt_box",
        messageSource,
        locale);
    boolDropdown(sheet, 8, locale);
  }

  private void applyBatchWindowValidations(Sheet sheet, Locale locale) {
    addDropdownValidation(
        sheet,
        6,
        END_STRATEGIES.toArray(String[]::new),
        "excel.window.end_strategy.prompt_title",
        "excel.window.end_strategy.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        sheet,
        7,
        OUT_OF_WINDOW_ACTIONS.toArray(String[]::new),
        "excel.window.out_of_window_action.prompt_title",
        "excel.window.out_of_window_action.prompt_box",
        messageSource,
        locale);
    boolDropdown(sheet, 8, locale);
    boolDropdown(sheet, 9, locale);
  }

  private void applyFileTemplateValidations(Sheet sheet, Locale locale) {
    addDropdownValidation(
        sheet,
        3,
        FILE_TEMPLATE_TYPES.toArray(String[]::new),
        "excel.template.template_type.prompt_title",
        "excel.template.template_type.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        sheet,
        5,
        FILE_FORMAT_TYPES.toArray(String[]::new),
        "excel.template.file_format_type.prompt_title",
        "excel.template.file_format_type.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        sheet,
        18,
        CHECKSUM_TYPES.toArray(String[]::new),
        "excel.template.checksum_type.prompt_title",
        "excel.template.checksum_type.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        sheet,
        19,
        COMPRESS_TYPES.toArray(String[]::new),
        "excel.template.compress_type.prompt_title",
        "excel.template.compress_type.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        sheet,
        20,
        ENCRYPT_TYPES.toArray(String[]::new),
        "excel.template.encrypt_type.prompt_title",
        "excel.template.encrypt_type.prompt_box",
        messageSource,
        locale);
    for (int col : FILE_TEMPLATE_BOOLEAN_COLUMNS) {
      boolDropdown(sheet, col, locale);
    }
  }

  private void applyPipelineValidations(Sheet sheet, Locale locale) {
    addDropdownValidation(
        sheet,
        3,
        PIPELINE_TYPES.toArray(String[]::new),
        "excel.pipeline.def.pipeline_type.prompt_title",
        "excel.pipeline.def.pipeline_type.prompt_box",
        messageSource,
        locale);
    boolDropdown(sheet, 7, locale);
  }

  void applyStepValidations(Sheet sheet, Locale locale) {
    applyStepValidations(sheet, locale, Map.of());
  }

  void applyStepValidations(
      Sheet sheet, Locale locale, Map<String, List<String>> registeredImplCodesByModule) {
    addDropdownValidation(
        sheet,
        4,
        STAGE_CODES.toArray(String[]::new),
        "excel.pipeline.step.stage_code.prompt_title",
        "excel.pipeline.step.stage_code.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        sheet,
        9,
        RETRY_POLICIES.toArray(String[]::new),
        "excel.pipeline.step.retry_policy.prompt_title",
        "excel.pipeline.step.retry_policy.prompt_box",
        messageSource,
        locale);
    // impl_code(第 7 列 index=6):动态下拉,格式 MODULE:beanName 让用户一眼看出模块归属。
    // registry 空时不加下拉(首次部署没 worker 启动过也能下载模板)。
    if (EmptyChecks.isNotEmpty(registeredImplCodesByModule)) {
      List<String> options = new ArrayList<>();
      registeredImplCodesByModule.forEach((module, beans) -> {
        for (String bean : beans) {
          options.add(module + ":" + bean);
        }
      });
      if (EmptyChecks.isNotEmpty(options)) {
        addDropdownValidation(
            sheet,
            6,
            options.toArray(String[]::new),
            "excel.package.impl_code.prompt_title",
            "excel.package.impl_code.prompt_box",
            messageSource,
            locale);
      }
    }
    boolDropdown(sheet, 11, locale);
  }

  private void applyWfDefValidations(Sheet sheet, Locale locale) {
    addDropdownValidation(
        sheet,
        3,
        WORKFLOW_TYPES.toArray(String[]::new),
        "excel.workflow.def.workflow_type.prompt_title",
        "excel.workflow.def.workflow_type.prompt_box",
        messageSource,
        locale);
    boolDropdown(sheet, 5, locale);
  }

  private void applyWfNodeValidations(Sheet sheet, Locale locale) {
    addDropdownValidation(
        sheet,
        5,
        NODE_TYPES.toArray(String[]::new),
        "excel.workflow.node.node_type.prompt_title",
        "excel.workflow.node.node_type.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        sheet,
        11,
        RETRY_POLICIES.toArray(String[]::new),
        "excel.workflow.node.retry_policy.prompt_title",
        "excel.workflow.node.retry_policy.prompt_box",
        messageSource,
        locale);
    boolDropdown(sheet, 15, locale);
  }

  private void applyWfEdgeValidations(Sheet sheet, Locale locale) {
    addDropdownValidation(
        sheet,
        5,
        EDGE_TYPES.toArray(String[]::new),
        "excel.workflow.edge.edge_type.prompt_title",
        "excel.workflow.edge.edge_type.prompt_box",
        messageSource,
        locale);
    boolDropdown(sheet, 7, locale);
  }

  private void boolDropdown(Sheet sheet, int columnIndex, Locale locale) {
    addDropdownValidation(
        sheet,
        columnIndex,
        new String[] {GUIDE_TRUE, GUIDE_FALSE},
        "excel.common.enabled.prompt_title",
        "excel.common.enabled.prompt_box",
        messageSource,
        locale);
  }

  private Map<String, ColumnGuide> buildJobGuides() {
    return Map.ofEntries(
        Map.entry(COL_TENANT_ID, optionalColumn("所属租户，留空使用当前租户。", GUIDE_STR, GUIDE_TENANT_EXAMPLE)),
        Map.entry(COL_JOB_CODE, requiredColumn("作业唯一编码。", GUIDE_STR, GUIDE_JOB_EXAMPLE)),
        Map.entry(COL_JOB_NAME, requiredColumn("作业名称。", GUIDE_STR, "客户导入作业")),
        Map.entry(
            COL_JOB_TYPE,
            requiredColumn(
                "作业类型。",
                GUIDE_ENUM,
                GUIDE_IMPORT,
                // 与 JobType enum / ConfigPackageExcelValidator.JOB_TYPES 对齐：
                // GENERAL / IMPORT / EXPORT / PROCESS / DISPATCH / WORKFLOW
                JOB_TYPE_DROPDOWN)),
        Map.entry(COL_BIZ_TYPE, optionalColumn("业务类型标识。", GUIDE_STR, "CUSTOMER")),
        Map.entry(COL_QUEUE_CODE, optionalColumn("资源队列编码。", GUIDE_STR, "import-queue")),
        Map.entry(COL_WORKER_GROUP, optionalColumn("Worker 分组。", GUIDE_STR, "import")),
        Map.entry(
            COL_SCHEDULE_TYPE,
            requiredColumn(
                "调度类型。",
                GUIDE_ENUM,
                "MANUAL",
                // 与 ScheduleType enum / ConfigPackageExcelValidator.SCHEDULE_TYPES 对齐：
                // CRON / FIXED_RATE / MANUAL（不再包含历史值 EVENT / ONE_TIME，validator 已拒收）
                SCHEDULE_TYPE_DROPDOWN)),
        Map.entry(COL_SCHEDULE_EXPR, optionalColumn("调度表达式，CRON 时填写。", GUIDE_STR, "0 2 * * *")),
        Map.entry(
            COL_DEPENDS_ON_JOB_CODE,
            optionalColumn(
                "上游作业编码；填写后 scheduled fire 前会等待同 bizDate 上游就绪。", GUIDE_STR, GUIDE_JOB_EXAMPLE)),
        Map.entry(COL_CALENDAR_CODE, optionalColumn("业务日历编码。", GUIDE_STR, "default-calendar")),
        Map.entry(COL_WINDOW_CODE, optionalColumn("批量窗口编码。", GUIDE_STR, "always-open")),
        Map.entry(
            COL_RETRY_POLICY,
            optionalColumn("重试策略。", GUIDE_ENUM, GUIDE_NONE, GUIDE_NONE, "FIXED", "EXPONENTIAL")),
        Map.entry(COL_RETRY_MAX_COUNT, optionalColumn("最大重试次数。", GUIDE_INT, "3")),
        Map.entry(COL_TIMEOUT_SECONDS, optionalColumn(GUIDE_TIMEOUT_DESC, GUIDE_INT, "3600")),
        Map.entry(
            COL_SHARD_STRATEGY,
            optionalColumn(
                "分片策略。", GUIDE_ENUM, GUIDE_NONE, GUIDE_NONE, "STATIC", "DYNAMIC", "AUTO")),
        Map.entry(
            COL_EXECUTION_MODE,
            optionalColumn(
                "执行模式。INCREMENTAL 需要同时填写 watermark_field。",
                GUIDE_ENUM,
                "FULL",
                "FULL",
                "INCREMENTAL",
                "CDC")),
        Map.entry(
            COL_WATERMARK_FIELD,
            optionalColumn("增量水位字段名；execution_mode=INCREMENTAL 时填写。", GUIDE_STR, "updated_at")),
        Map.entry(
            COL_EXECUTION_HANDLER,
            optionalColumn("执行处理器 Bean 名称（新建时设置，更新时忽略）。", GUIDE_STR, "importJobHandler")),
        Map.entry(
            COL_PARAM_SCHEMA,
            optionalColumn("参数 JSON Schema（新建时设置，更新时忽略）。", GUIDE_JSON, GUIDE_EMPTY_JSON)),
        Map.entry(
            COL_DEFAULT_PARAMS,
            optionalColumn(
                "默认参数 JSON（新建时设置，更新时忽略）。IMPORT/EXPORT 用 templateCode 引用 file_template_config。",
                GUIDE_JSON,
                EXAMPLE_JOB_DEFAULT_PARAMS)),
        Map.entry(
            COL_ENABLED,
            optionalColumn(GUIDE_ENABLED_DESC, GUIDE_BOOL, GUIDE_TRUE, GUIDE_TRUE, GUIDE_FALSE)),
        Map.entry(COL_DESCRIPTION, optionalColumn(GUIDE_DESC_DESC, GUIDE_STR, "客户文件导入作业")));
  }

  private Map<String, ColumnGuide> buildChannelGuides() {
    return Map.ofEntries(
        Map.entry(
            COL_TENANT_ID, optionalColumn(GUIDE_TENANT_DESC, GUIDE_STR, GUIDE_TENANT_EXAMPLE)),
        Map.entry(COL_CHANNEL_CODE, requiredColumn("通道唯一编码。", GUIDE_STR, "sftp_inbound")),
        Map.entry(COL_CHANNEL_NAME, requiredColumn("通道名称。", GUIDE_STR, "SFTP 入站通道")),
        Map.entry(
            COL_CHANNEL_TYPE,
            requiredColumn(
                "通道类型。",
                GUIDE_ENUM,
                "SFTP",
                "SFTP",
                "API",
                "EMAIL",
                "NAS",
                "OSS",
                "LOCAL",
                "API_PUSH")),
        Map.entry("target_endpoint", optionalColumn("目标地址。", GUIDE_STR, "sftp.example.com")),
        Map.entry(
            COL_AUTH_TYPE,
            requiredColumn(
                "认证类型。",
                GUIDE_ENUM,
                "PASSWORD",
                GUIDE_NONE,
                "PASSWORD",
                "KEY_PAIR",
                "TOKEN",
                "OAUTH2",
                "CUSTOM")),
        Map.entry(
            COL_CONFIG_JSON,
            requiredColumn(
                "通道配置 JSON：endpoint + auth + credentials（DISPATCH 用；endpoint 为 env-specific，跨环境迁移需"
                    + " review）。",
                GUIDE_JSON,
                EXAMPLE_CHANNEL_CONFIG_JSON)),
        Map.entry(
            COL_RECEIPT_POLICY,
            requiredColumn(
                "回执策略。", GUIDE_ENUM, GUIDE_NONE, GUIDE_NONE, "SYNC", "ASYNC", "POLLING")),
        Map.entry(COL_TIMEOUT_SECONDS, optionalColumn(GUIDE_TIMEOUT_DESC, GUIDE_INT, "30")),
        Map.entry(
            COL_ENABLED,
            optionalColumn(GUIDE_ENABLED_DESC, GUIDE_BOOL, GUIDE_TRUE, GUIDE_TRUE, GUIDE_FALSE)));
  }

  private Map<String, ColumnGuide> buildFileTemplateGuides() {
    return Map.ofEntries(
        Map.entry(
            COL_TENANT_ID, optionalColumn(GUIDE_TENANT_DESC, GUIDE_STR, GUIDE_TENANT_EXAMPLE)),
        Map.entry("template_code", requiredColumn("文件模板唯一编码。", GUIDE_STR, "TPL_IMPORT_CUSTOMER")),
        Map.entry("template_name", requiredColumn("文件模板名称。", GUIDE_STR, "客户导入模板")),
        Map.entry(
            "template_type",
            requiredColumn("模板类型。", GUIDE_ENUM, GUIDE_IMPORT, GUIDE_IMPORT, "EXPORT", "SHARED")),
        Map.entry(COL_BIZ_TYPE, optionalColumn("业务类型。", GUIDE_STR, "CUSTOMER")),
        Map.entry(
            "file_format_type",
            requiredColumn(
                "文件格式。",
                GUIDE_ENUM,
                "DELIMITED",
                "DELIMITED",
                "FIXED_WIDTH",
                "EXCEL",
                "XML",
                "JSON",
                "BINARY")),
        Map.entry("charset", optionalColumn("源文件字符集。", GUIDE_STR, "UTF-8")),
        Map.entry("target_charset", optionalColumn("导出目标字符集。", GUIDE_STR, "UTF-8")),
        Map.entry(
            "with_bom",
            optionalColumn("是否带 BOM。", GUIDE_BOOL, GUIDE_FALSE, GUIDE_TRUE, GUIDE_FALSE)),
        Map.entry("line_separator", optionalColumn("换行符。", GUIDE_STR, "\\n")),
        Map.entry("delimiter", optionalColumn("分隔符。", GUIDE_STR, ",")),
        Map.entry("quote_char", optionalColumn("引用符。", GUIDE_STR, "\"")),
        Map.entry("escape_char", optionalColumn("转义符。", GUIDE_STR, "\\")),
        Map.entry("record_length", optionalColumn("定长文件记录长度。", GUIDE_INT, "0")),
        Map.entry("header_rows", optionalColumn("导入跳过头部行数。", GUIDE_INT, "1")),
        Map.entry("footer_rows", optionalColumn("导入跳过尾部行数。", GUIDE_INT, "0")),
        Map.entry("header_template", optionalColumn("导出头部模板 JSON。", GUIDE_JSON, GUIDE_EMPTY_JSON)),
        Map.entry("trailer_template", optionalColumn("导出尾部模板 JSON。", GUIDE_JSON, GUIDE_EMPTY_JSON)),
        Map.entry(
            "checksum_type",
            optionalColumn("校验类型。", GUIDE_ENUM, GUIDE_NONE, GUIDE_NONE, "MD5", "SHA256")),
        Map.entry(
            "compress_type",
            optionalColumn("压缩类型。", GUIDE_ENUM, GUIDE_NONE, GUIDE_NONE, "ZIP", "GZIP")),
        Map.entry(
            "encrypt_type",
            optionalColumn("加密类型。", GUIDE_ENUM, GUIDE_NONE, GUIDE_NONE, "PGP", "AES")),
        Map.entry("naming_rule", optionalColumn("文件命名规则。", GUIDE_STR, "customer_${batchDate}.csv")),
        Map.entry(
            "field_mappings",
            optionalColumn(
                "字段映射 JSON。IMPORT 用 source(name)/targetColumn/type/required/maxLength/format；EXPORT"
                    + " 用 sourceColumn/header/type/format(numberFormat 如 #,##0.00)。两套结构不同，见填写示例列。",
                GUIDE_JSON,
                EXAMPLE_IMPORT_FIELD_MAPPINGS)),
        Map.entry(
            "validation_rule_set",
            optionalColumn(
                "校验规则 JSON（IMPORT）。maxErrorRate/stopOnFirstError/duplicateKeyCheck。",
                GUIDE_JSON,
                EXAMPLE_IMPORT_VALIDATION_RULE_SET)),
        Map.entry(
            "default_query_code", optionalColumn("默认查询编码。", GUIDE_STR, "QRY_CUSTOMER_EXPORT")),
        Map.entry(
            "default_query_sql",
            optionalColumn(
                "默认导出 SQL（EXPORT only，单条安全 SELECT，命名参数 :tenantId 等）。",
                GUIDE_SQL,
                EXAMPLE_EXPORT_QUERY_SQL)),
        Map.entry(
            "query_param_schema",
            optionalColumn(
                "查询参数 JSON。IMPORT 用 jdbcMappedImport(schema/table/columnMappings/"
                    + "conflictColumns/systemBindings)；EXPORT 用 JSON Schema 参数声明或 "
                    + "sqlTemplateExport.cursorColumn / jdbcMappedExport。两套结构不同，见填写示例列。",
                GUIDE_JSON,
                EXAMPLE_IMPORT_QUERY_PARAM_SCHEMA)),
        Map.entry(
            "streaming_enabled",
            optionalColumn("是否流式处理。", GUIDE_BOOL, GUIDE_TRUE, GUIDE_TRUE, GUIDE_FALSE)),
        Map.entry("page_size", optionalColumn("分页大小。", GUIDE_INT, "1000")),
        Map.entry("fetch_size", optionalColumn("JDBC fetch size。", GUIDE_INT, "1000")),
        Map.entry("chunk_size", optionalColumn("分块大小。", GUIDE_INT, "500")),
        Map.entry(
            "preview_masking_enabled",
            optionalColumn("预览是否脱敏。", GUIDE_BOOL, GUIDE_FALSE, GUIDE_TRUE, GUIDE_FALSE)),
        Map.entry(
            "error_line_masking_enabled",
            optionalColumn("错误行是否脱敏。", GUIDE_BOOL, GUIDE_FALSE, GUIDE_TRUE, GUIDE_FALSE)),
        Map.entry(
            "log_masking_enabled",
            optionalColumn("日志是否脱敏。", GUIDE_BOOL, GUIDE_TRUE, GUIDE_TRUE, GUIDE_FALSE)),
        Map.entry(
            "content_encryption_enabled",
            optionalColumn("内容是否加密。", GUIDE_BOOL, GUIDE_FALSE, GUIDE_TRUE, GUIDE_FALSE)),
        Map.entry(
            "encryption_key_ref",
            optionalColumn("密钥引用。", GUIDE_STR, "kms://file-template/customer")),
        Map.entry(
            "download_requires_approval",
            optionalColumn("下载是否需要审批。", GUIDE_BOOL, GUIDE_FALSE, GUIDE_TRUE, GUIDE_FALSE)),
        Map.entry("masking_rule_set", optionalColumn("脱敏规则集编码。", GUIDE_STR, "MASK_CUSTOMER")),
        Map.entry(
            COL_ENABLED,
            optionalColumn(GUIDE_ENABLED_DESC, GUIDE_BOOL, GUIDE_TRUE, GUIDE_TRUE, GUIDE_FALSE)),
        Map.entry(COL_VERSION, optionalColumn("版本号。", GUIDE_INT, GUIDE_VERSION_ONE)),
        Map.entry(COL_DESCRIPTION, optionalColumn(GUIDE_DESC_DESC, GUIDE_STR, "客户导入文件模板")));
  }

  private Map<String, ColumnGuide> buildPipelineGuides() {
    return Map.ofEntries(
        Map.entry(
            COL_TENANT_ID, optionalColumn(GUIDE_TENANT_DESC, GUIDE_STR, GUIDE_TENANT_EXAMPLE)),
        Map.entry(
            COL_JOB_CODE, requiredColumn("关联作业编码，与 version 组成联合键。", GUIDE_STR, GUIDE_JOB_EXAMPLE)),
        Map.entry(COL_PIPELINE_NAME, requiredColumn("流水线名称。", GUIDE_STR, "客户导入流水线")),
        Map.entry(
            COL_PIPELINE_TYPE,
            requiredColumn(
                "流水线类型。",
                GUIDE_ENUM,
                GUIDE_IMPORT,
                // 与 PipelineType enum / ConfigPackageExcelValidator.PIPELINE_TYPES 对齐：
                // IMPORT / EXPORT / PROCESS / DISPATCH
                PIPELINE_TYPE_DROPDOWN)),
        Map.entry(COL_BIZ_TYPE, optionalColumn("业务类型。", GUIDE_STR, "CUSTOMER")),
        Map.entry(COL_WORKER_GROUP, optionalColumn("Worker 分组。", GUIDE_STR, "import")),
        Map.entry(
            COL_VERSION, requiredColumn("版本号，与 job_code 组成联合键。", GUIDE_INT, GUIDE_VERSION_ONE)),
        Map.entry(
            COL_ENABLED,
            optionalColumn(GUIDE_ENABLED_DESC, GUIDE_BOOL, GUIDE_TRUE, GUIDE_TRUE, GUIDE_FALSE)),
        Map.entry(COL_DESCRIPTION, optionalColumn(GUIDE_DESC_DESC, GUIDE_STR, "客户文件导入流水线")));
  }

  private Map<String, ColumnGuide> buildStepGuides() {
    return Map.ofEntries(
        Map.entry(COL_JOB_CODE, requiredColumn("关联流水线的 job_code。", GUIDE_STR, GUIDE_JOB_EXAMPLE)),
        Map.entry(COL_VERSION, requiredColumn("关联流水线的版本号。", GUIDE_INT, GUIDE_VERSION_ONE)),
        Map.entry(COL_STEP_CODE, requiredColumn("步骤唯一编码。", GUIDE_STR, "STEP_PARSE")),
        Map.entry(COL_STEP_NAME, requiredColumn("步骤名称。", GUIDE_STR, "解析文件")),
        Map.entry(
            COL_STAGE_CODE,
            requiredColumn(
                "阶段。按 pipeline_type 选对应"
                    + " stage：IMPORT[RECEIVE,PREPROCESS,PARSE,VALIDATE,LOAD,FEEDBACK]"
                    + "；EXPORT[PREPARE,GENERATE,STORE,REGISTER,COMPLETE]"
                    + "；PROCESS[PREPARE,COMPUTE,VALIDATE,COMMIT,FEEDBACK]"
                    + "；DISPATCH[PREPARE,DISPATCH,ACK,RETRY,COMPENSATE,COMPLETE]。",
                GUIDE_ENUM,
                "PARSE",
                // 17 个 stage 的 union，validator 按 pipeline_type 进一步收窄（STAGES_BY_TYPE）；
                // 旧值 TRANSFER 已删除（validator 不接受），文档侧不再出现
                STAGE_CODE_DROPDOWN)),
        Map.entry("step_order", optionalColumn("步骤顺序号。", GUIDE_INT, GUIDE_VERSION_ONE)),
        Map.entry("impl_code", optionalColumn("实现插件编码。", GUIDE_STR, "csvParser")),
        Map.entry(
            "step_params",
            optionalColumn(
                "步骤参数 JSON。IMPORT/EXPORT 用 templateCode 引用模板；PROCESS 用"
                    + " targetSchema/targetTable/sql/columnMappings；DISPATCH 用 channelCode 引用"
                    + " file_channel_config。见填写示例列。",
                GUIDE_JSON,
                EXAMPLE_PROCESS_STEP_PARAMS)),
        Map.entry(COL_TIMEOUT_SECONDS, optionalColumn(GUIDE_TIMEOUT_DESC, GUIDE_INT, "300")),
        Map.entry(
            COL_RETRY_POLICY,
            optionalColumn("重试策略。", GUIDE_ENUM, GUIDE_NONE, GUIDE_NONE, "FIXED", "EXPONENTIAL")),
        Map.entry(COL_RETRY_MAX_COUNT, optionalColumn("最大重试次数。", GUIDE_INT, "0")),
        Map.entry(
            COL_ENABLED,
            optionalColumn(GUIDE_ENABLED_DESC, GUIDE_BOOL, GUIDE_TRUE, GUIDE_TRUE, GUIDE_FALSE)));
  }

  private Map<String, ColumnGuide> buildWfDefGuides() {
    return Map.ofEntries(
        Map.entry(
            COL_TENANT_ID, optionalColumn(GUIDE_TENANT_DESC, GUIDE_STR, GUIDE_TENANT_EXAMPLE)),
        Map.entry(
            COL_WORKFLOW_CODE,
            requiredColumn("工作流唯一编码，三个工作流 sheet 共用此键。", GUIDE_STR, "WF_SETTLEMENT")),
        Map.entry(COL_WORKFLOW_NAME, requiredColumn("工作流名称。", GUIDE_STR, "清算工作流")),
        Map.entry(
            COL_WORKFLOW_TYPE,
            requiredColumn("工作流拓扑类型。", GUIDE_ENUM, "DAG", "DAG", "PIPELINE", "MIXED")),
        Map.entry(COL_VERSION, requiredColumn("版本号。", GUIDE_INT, GUIDE_VERSION_ONE)),
        Map.entry(
            COL_ENABLED,
            optionalColumn(GUIDE_ENABLED_DESC, GUIDE_BOOL, GUIDE_TRUE, GUIDE_TRUE, GUIDE_FALSE)),
        Map.entry(COL_DESCRIPTION, optionalColumn(GUIDE_DESC_DESC, GUIDE_STR, "清算批量工作流")));
  }

  private Map<String, ColumnGuide> buildWfNodeGuides() {
    return Map.ofEntries(
        Map.entry(
            COL_TENANT_ID, optionalColumn(GUIDE_TENANT_DESC, GUIDE_STR, GUIDE_TENANT_EXAMPLE)),
        Map.entry(COL_WORKFLOW_CODE, requiredColumn("所属工作流编码。", GUIDE_STR, "WF_SETTLEMENT")),
        Map.entry(COL_WORKFLOW_VERSION, requiredColumn("所属工作流版本号。", GUIDE_INT, GUIDE_VERSION_ONE)),
        Map.entry(COL_NODE_CODE, requiredColumn("节点唯一编码。", GUIDE_STR, "NODE_IMPORT")),
        Map.entry(COL_NODE_NAME, requiredColumn("节点名称。", GUIDE_STR, "导入节点")),
        Map.entry(
            COL_NODE_TYPE,
            requiredColumn(
                "节点类型。", GUIDE_ENUM, "JOB", "TASK", "GATEWAY", "FILE_STEP", "START", "END", "JOB")),
        Map.entry(
            COL_RELATED_JOB_CODE,
            optionalColumn(
                "关联的作业编码，需在本包 job_definition sheet 或库中存在。", GUIDE_STR, GUIDE_JOB_EXAMPLE)),
        Map.entry(
            COL_RELATED_PIPELINE_CODE,
            optionalColumn(
                "关联的流水线 job_code，需在本包 pipeline_definition sheet 或库中存在。",
                GUIDE_STR,
                GUIDE_JOB_EXAMPLE)),
        Map.entry(COL_WORKER_GROUP, optionalColumn("Worker 分组。", GUIDE_STR, "import")),
        Map.entry(COL_WINDOW_CODE, optionalColumn("批量窗口编码。", GUIDE_STR, "always-open")),
        Map.entry(COL_NODE_ORDER, optionalColumn("节点顺序号。", GUIDE_INT, GUIDE_VERSION_ONE)),
        Map.entry(
            COL_RETRY_POLICY,
            optionalColumn("重试策略。", GUIDE_ENUM, GUIDE_NONE, GUIDE_NONE, "FIXED", "EXPONENTIAL")),
        Map.entry(COL_RETRY_MAX_COUNT, optionalColumn("最大重试次数。", GUIDE_INT, "0")),
        Map.entry(COL_TIMEOUT_SECONDS, optionalColumn(GUIDE_TIMEOUT_DESC, GUIDE_INT, "3600")),
        Map.entry(COL_NODE_PARAMS, optionalColumn("节点参数 JSON。", GUIDE_JSON, GUIDE_EMPTY_JSON)),
        Map.entry(
            COL_ENABLED,
            optionalColumn(GUIDE_ENABLED_DESC, GUIDE_BOOL, GUIDE_TRUE, GUIDE_TRUE, GUIDE_FALSE)));
  }

  private Map<String, ColumnGuide> buildWfEdgeGuides() {
    return Map.ofEntries(
        Map.entry(
            COL_TENANT_ID, optionalColumn(GUIDE_TENANT_DESC, GUIDE_STR, GUIDE_TENANT_EXAMPLE)),
        Map.entry(COL_WORKFLOW_CODE, requiredColumn("所属工作流编码。", GUIDE_STR, "WF_SETTLEMENT")),
        Map.entry(COL_WORKFLOW_VERSION, requiredColumn("所属工作流版本号。", GUIDE_INT, GUIDE_VERSION_ONE)),
        Map.entry(COL_FROM_NODE_CODE, requiredColumn("源节点编码。", GUIDE_STR, "NODE_IMPORT")),
        Map.entry(COL_TO_NODE_CODE, requiredColumn("目标节点编码。", GUIDE_STR, "NODE_EXPORT")),
        Map.entry(
            COL_EDGE_TYPE,
            requiredColumn(
                "边类型。", GUIDE_ENUM, "SUCCESS", "SUCCESS", "FAILURE", "CONDITION", "ALWAYS")),
        Map.entry(COL_CONDITION_EXPR, optionalColumn("CONDITION 类型的条件表达式。", GUIDE_STR, EMPTY)),
        Map.entry(
            COL_ENABLED,
            optionalColumn(GUIDE_ENABLED_DESC, GUIDE_BOOL, GUIDE_TRUE, GUIDE_TRUE, GUIDE_FALSE)));
  }
}
