package io.github.pinpols.batch.console.infrastructure.excel;

import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_AUTH_TYPE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_BIZ_TYPE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_CALENDAR_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_CHANNEL_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_CHANNEL_NAME;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_CHANNEL_TYPE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_CONDITION_EXPR;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_CONFIG_JSON;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_DEFAULT_PARAMS;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 租户配置包工作簿中每个 sheet 的列 schema。 */
public final class ConfigPackageExcelSchema {

  private ConfigPackageExcelSchema() {}

  public static final class ResourceQueue {
    public static final String COL_QUEUE_NAME = "queue_name";
    public static final String COL_QUEUE_TYPE = "queue_type";
    public static final String COL_MAX_RUNNING_JOBS = "max_running_jobs";
    public static final String COL_MAX_RUNNING_PARTITIONS = "max_running_partitions";
    public static final String COL_MAX_QPS = "max_qps";
    public static final String COL_RESOURCE_TAG = "resource_tag";
    public static final String COL_PRIORITY_POLICY = "priority_policy";
    public static final String COL_FAIR_SHARE_WEIGHT = "fair_share_weight";

    public static final List<String> COLUMNS =
        List.of(
            COL_TENANT_ID,
            COL_QUEUE_CODE,
            COL_QUEUE_NAME,
            COL_QUEUE_TYPE,
            COL_MAX_RUNNING_JOBS,
            COL_MAX_RUNNING_PARTITIONS,
            COL_MAX_QPS,
            COL_WORKER_GROUP,
            COL_RESOURCE_TAG,
            COL_PRIORITY_POLICY,
            COL_FAIR_SHARE_WEIGHT,
            COL_ENABLED,
            COL_DESCRIPTION);

    private ResourceQueue() {}
  }

  public static final class BusinessCalendar {
    public static final String SHEET_NAME = "business_calendar";
    public static final String COL_CALENDAR_CODE = ConfigPackageExcelValidator.COL_CALENDAR_CODE;
    public static final String COL_CALENDAR_NAME = "calendar_name";
    public static final String COL_TIMEZONE = "timezone";
    public static final String COL_HOLIDAY_ROLL_RULE = "holiday_roll_rule";
    public static final String COL_CATCH_UP_POLICY = "catch_up_policy";
    public static final String COL_CATCH_UP_MAX_DAYS = "catch_up_max_days";
    public static final String COL_HOLIDAYS = "holidays";

    public static final String ROW_TENANT_ID = "tenantId";
    public static final String ROW_CALENDAR_CODE = "calendarCode";
    public static final String ROW_CALENDAR_NAME = "calendarName";
    public static final String ROW_TIMEZONE = "timezone";
    public static final String ROW_HOLIDAY_ROLL_RULE = "holidayRollRule";
    public static final String ROW_CATCH_UP_POLICY = "catchUpPolicy";
    public static final String ROW_CATCH_UP_MAX_DAYS = "catchUpMaxDays";

    public static final List<String> COLUMNS =
        List.of(
            COL_TENANT_ID,
            COL_CALENDAR_CODE,
            COL_CALENDAR_NAME,
            COL_TIMEZONE,
            COL_HOLIDAY_ROLL_RULE,
            COL_CATCH_UP_POLICY,
            COL_CATCH_UP_MAX_DAYS,
            COL_HOLIDAYS,
            COL_ENABLED,
            COL_DESCRIPTION);

    private BusinessCalendar() {}

    public static Map<String, Object> toExportRow(Map<String, Object> row, String holidaysText) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put(COL_TENANT_ID, row.get(ROW_TENANT_ID));
      item.put(COL_CALENDAR_CODE, row.get(ROW_CALENDAR_CODE));
      item.put(COL_CALENDAR_NAME, row.get(ROW_CALENDAR_NAME));
      item.put(COL_TIMEZONE, row.get(ROW_TIMEZONE));
      item.put(COL_HOLIDAY_ROLL_RULE, row.get(ROW_HOLIDAY_ROLL_RULE));
      item.put(COL_CATCH_UP_POLICY, row.get(ROW_CATCH_UP_POLICY));
      item.put(COL_CATCH_UP_MAX_DAYS, row.get(ROW_CATCH_UP_MAX_DAYS));
      item.put(COL_HOLIDAYS, holidaysText);
      item.put(COL_ENABLED, row.get(COL_ENABLED));
      item.put(COL_DESCRIPTION, row.get(COL_DESCRIPTION));
      return item;
    }
  }

  public static final class BatchWindow {
    public static final String COL_WINDOW_NAME = "window_name";
    public static final String COL_TIMEZONE = "timezone";
    public static final String COL_START_TIME = "start_time";
    public static final String COL_END_TIME = "end_time";
    public static final String COL_END_STRATEGY = "end_strategy";
    public static final String COL_OUT_OF_WINDOW_ACTION = "out_of_window_action";
    public static final String COL_ALLOW_CROSS_DAY = "allow_cross_day";

    public static final List<String> COLUMNS =
        List.of(
            COL_TENANT_ID,
            COL_WINDOW_CODE,
            COL_WINDOW_NAME,
            COL_TIMEZONE,
            COL_START_TIME,
            COL_END_TIME,
            COL_END_STRATEGY,
            COL_OUT_OF_WINDOW_ACTION,
            COL_ALLOW_CROSS_DAY,
            COL_ENABLED,
            COL_DESCRIPTION);

    private BatchWindow() {}
  }

  public static final class JobDefinition {
    public static final List<String> COLUMNS =
        List.of(
            COL_TENANT_ID,
            COL_JOB_CODE,
            COL_JOB_NAME,
            COL_JOB_TYPE,
            COL_BIZ_TYPE,
            COL_QUEUE_CODE,
            COL_WORKER_GROUP,
            COL_SCHEDULE_TYPE,
            COL_SCHEDULE_EXPR,
            COL_CALENDAR_CODE,
            COL_WINDOW_CODE,
            COL_RETRY_POLICY,
            COL_RETRY_MAX_COUNT,
            COL_TIMEOUT_SECONDS,
            COL_SHARD_STRATEGY,
            COL_EXECUTION_MODE,
            COL_WATERMARK_FIELD,
            COL_EXECUTION_HANDLER,
            COL_PARAM_SCHEMA,
            COL_DEFAULT_PARAMS,
            COL_ENABLED,
            COL_DESCRIPTION);

    private JobDefinition() {}
  }

  public static final class FileChannel {
    public static final String COL_TARGET_ENDPOINT = "target_endpoint";

    public static final List<String> COLUMNS =
        List.of(
            COL_TENANT_ID,
            COL_CHANNEL_CODE,
            COL_CHANNEL_NAME,
            COL_CHANNEL_TYPE,
            COL_TARGET_ENDPOINT,
            COL_AUTH_TYPE,
            COL_CONFIG_JSON,
            COL_RECEIPT_POLICY,
            COL_TIMEOUT_SECONDS,
            COL_ENABLED);

    private FileChannel() {}
  }

  public static final class FileTemplate {
    public static final String COL_TEMPLATE_CODE = "template_code";
    public static final String COL_TEMPLATE_NAME = "template_name";
    public static final String COL_TEMPLATE_TYPE = "template_type";
    public static final String COL_FILE_FORMAT_TYPE = "file_format_type";
    public static final String COL_CHARSET = "charset";
    public static final String COL_TARGET_CHARSET = "target_charset";
    public static final String COL_WITH_BOM = "with_bom";
    public static final String COL_LINE_SEPARATOR = "line_separator";
    public static final String COL_DELIMITER = "delimiter";
    public static final String COL_QUOTE_CHAR = "quote_char";
    public static final String COL_ESCAPE_CHAR = "escape_char";
    public static final String COL_RECORD_LENGTH = "record_length";
    public static final String COL_HEADER_ROWS = "header_rows";
    public static final String COL_FOOTER_ROWS = "footer_rows";
    public static final String COL_HEADER_TEMPLATE = "header_template";
    public static final String COL_TRAILER_TEMPLATE = "trailer_template";
    public static final String COL_CHECKSUM_TYPE = "checksum_type";
    public static final String COL_COMPRESS_TYPE = "compress_type";
    public static final String COL_ENCRYPT_TYPE = "encrypt_type";
    public static final String COL_NAMING_RULE = "naming_rule";
    public static final String COL_FIELD_MAPPINGS = "field_mappings";
    public static final String COL_VALIDATION_RULE_SET = "validation_rule_set";
    public static final String COL_DEFAULT_QUERY_CODE = "default_query_code";
    public static final String COL_DEFAULT_QUERY_SQL = "default_query_sql";
    public static final String COL_QUERY_PARAM_SCHEMA = "query_param_schema";
    public static final String COL_STREAMING_ENABLED = "streaming_enabled";
    public static final String COL_PAGE_SIZE = "page_size";
    public static final String COL_FETCH_SIZE = "fetch_size";
    public static final String COL_CHUNK_SIZE = "chunk_size";
    public static final String COL_PREVIEW_MASKING_ENABLED = "preview_masking_enabled";
    public static final String COL_ERROR_LINE_MASKING_ENABLED = "error_line_masking_enabled";
    public static final String COL_LOG_MASKING_ENABLED = "log_masking_enabled";
    public static final String COL_CONTENT_ENCRYPTION_ENABLED = "content_encryption_enabled";
    public static final String COL_ENCRYPTION_KEY_REF = "encryption_key_ref";
    public static final String COL_DOWNLOAD_REQUIRES_APPROVAL = "download_requires_approval";
    public static final String COL_MASKING_RULE_SET = "masking_rule_set";

    public static final List<String> COLUMNS =
        List.of(
            COL_TENANT_ID,
            COL_TEMPLATE_CODE,
            COL_TEMPLATE_NAME,
            COL_TEMPLATE_TYPE,
            COL_BIZ_TYPE,
            COL_FILE_FORMAT_TYPE,
            COL_CHARSET,
            COL_TARGET_CHARSET,
            COL_WITH_BOM,
            COL_LINE_SEPARATOR,
            COL_DELIMITER,
            COL_QUOTE_CHAR,
            COL_ESCAPE_CHAR,
            COL_RECORD_LENGTH,
            COL_HEADER_ROWS,
            COL_FOOTER_ROWS,
            COL_HEADER_TEMPLATE,
            COL_TRAILER_TEMPLATE,
            COL_CHECKSUM_TYPE,
            COL_COMPRESS_TYPE,
            COL_ENCRYPT_TYPE,
            COL_NAMING_RULE,
            COL_FIELD_MAPPINGS,
            COL_VALIDATION_RULE_SET,
            COL_DEFAULT_QUERY_CODE,
            COL_DEFAULT_QUERY_SQL,
            COL_QUERY_PARAM_SCHEMA,
            COL_STREAMING_ENABLED,
            COL_PAGE_SIZE,
            COL_FETCH_SIZE,
            COL_CHUNK_SIZE,
            COL_PREVIEW_MASKING_ENABLED,
            COL_ERROR_LINE_MASKING_ENABLED,
            COL_LOG_MASKING_ENABLED,
            COL_CONTENT_ENCRYPTION_ENABLED,
            COL_ENCRYPTION_KEY_REF,
            COL_DOWNLOAD_REQUIRES_APPROVAL,
            COL_MASKING_RULE_SET,
            COL_ENABLED,
            COL_VERSION,
            COL_DESCRIPTION);

    private FileTemplate() {}
  }

  public static final class PipelineDefinition {
    public static final List<String> COLUMNS =
        List.of(
            COL_TENANT_ID,
            COL_JOB_CODE,
            COL_PIPELINE_NAME,
            COL_PIPELINE_TYPE,
            COL_BIZ_TYPE,
            COL_WORKER_GROUP,
            COL_VERSION,
            COL_ENABLED,
            COL_DESCRIPTION);

    private PipelineDefinition() {}
  }

  public static final class PipelineStep {
    public static final String COL_STEP_ORDER = "step_order";
    public static final String COL_IMPL_CODE = "impl_code";
    public static final String COL_STEP_PARAMS = "step_params";

    public static final List<String> COLUMNS =
        List.of(
            COL_JOB_CODE,
            COL_VERSION,
            COL_STEP_CODE,
            COL_STEP_NAME,
            COL_STAGE_CODE,
            COL_STEP_ORDER,
            COL_IMPL_CODE,
            COL_STEP_PARAMS,
            COL_TIMEOUT_SECONDS,
            COL_RETRY_POLICY,
            COL_RETRY_MAX_COUNT,
            COL_ENABLED);

    private PipelineStep() {}
  }

  public static final class WorkflowDefinition {
    public static final List<String> COLUMNS =
        List.of(
            COL_TENANT_ID,
            COL_WORKFLOW_CODE,
            COL_WORKFLOW_NAME,
            COL_WORKFLOW_TYPE,
            COL_VERSION,
            COL_ENABLED,
            COL_DESCRIPTION);

    private WorkflowDefinition() {}
  }

  public static final class WorkflowNode {
    public static final List<String> COLUMNS =
        List.of(
            COL_TENANT_ID,
            COL_WORKFLOW_CODE,
            COL_WORKFLOW_VERSION,
            COL_NODE_CODE,
            COL_NODE_NAME,
            COL_NODE_TYPE,
            COL_RELATED_JOB_CODE,
            COL_RELATED_PIPELINE_CODE,
            COL_WORKER_GROUP,
            COL_WINDOW_CODE,
            COL_NODE_ORDER,
            COL_RETRY_POLICY,
            COL_RETRY_MAX_COUNT,
            COL_TIMEOUT_SECONDS,
            COL_NODE_PARAMS,
            COL_ENABLED);

    private WorkflowNode() {}
  }

  public static final class WorkflowEdge {
    public static final List<String> COLUMNS =
        List.of(
            COL_TENANT_ID,
            COL_WORKFLOW_CODE,
            COL_WORKFLOW_VERSION,
            COL_FROM_NODE_CODE,
            COL_TO_NODE_CODE,
            COL_EDGE_TYPE,
            COL_CONDITION_EXPR,
            COL_ENABLED);

    private WorkflowEdge() {}
  }
}
