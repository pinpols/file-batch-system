package io.github.pinpols.batch.console.infrastructure.excel;

import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.*;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 生成 11-Sheet 配置包的场景化示例数据。示例值只用于模板引导，不代表生产默认配置。 */
public final class ConfigPackageSampleDataFactory {

  private static final String TENANT = "demo-tenant";
  private static final String CALENDAR = "default-calendar";
  private static final String WINDOW = "always-open";
  private static final String QUEUE = "demo-queue";
  private static final String CHANNEL = "demo-sftp";
  private static final String VERSION = "1";
  private static final String SCENARIO_ALL = "ALL";
  private static final String SCENARIO_IMPORT = "IMPORT";
  private static final String SCENARIO_EXPORT = "EXPORT";
  private static final String SCENARIO_PROCESS = "PROCESS";
  private static final String SCENARIO_DISPATCH = "DISPATCH";
  private static final String SCENARIO_ATOMIC = "ATOMIC";
  private static final String SCENARIO_WORKFLOW = "WORKFLOW";
  private static final int SHEET_COUNT = 11;

  private ConfigPackageSampleDataFactory() {}

  public static List<List<Map<String, Object>>> sampleSheets(String rawScenario) {
    String scenario = normalizeScenario(rawScenario);
    return sampleSheets(List.of(scenario));
  }

  public static List<List<Map<String, Object>>> sampleSheets(List<String> rawScenarios) {
    Set<String> scenarios = normalizeScenarios(rawScenarios);
    List<List<Map<String, Object>>> sheets = emptySheets();
    putCommonFoundation(sheets, scenarios.size() == 1 ? scenarios.iterator().next() : SCENARIO_ALL);
    for (String scenario : scenarios) {
      switch (scenario) {
        case SCENARIO_IMPORT -> putImport(sheets);
        case SCENARIO_EXPORT -> putExport(sheets);
        case SCENARIO_PROCESS -> putProcess(sheets);
        case SCENARIO_DISPATCH -> putDispatch(sheets);
        case SCENARIO_ATOMIC -> putAtomic(sheets);
        case SCENARIO_WORKFLOW -> putWorkflow(sheets);
        default -> {
          putImport(sheets);
          putExport(sheets);
          putProcess(sheets);
          putDispatch(sheets);
          putAtomic(sheets);
          putWorkflow(sheets);
        }
      }
    }
    return sheets;
  }

  public static Set<String> normalizeScenarios(List<String> rawScenarios) {
    if (rawScenarios == null || rawScenarios.isEmpty()) {
      return Set.of(SCENARIO_ALL);
    }
    LinkedHashSet<String> normalized = new LinkedHashSet<>();
    for (String rawScenario : rawScenarios) {
      String scenario = normalizeScenario(rawScenario);
      if (SCENARIO_ALL.equals(scenario)) {
        return Set.of(SCENARIO_ALL);
      }
      normalized.add(scenario);
    }
    return normalized.isEmpty() ? Set.of(SCENARIO_ALL) : normalized;
  }

  public static String normalizeScenario(String rawScenario) {
    if (EmptyChecks.isBlank(rawScenario)) {
      return SCENARIO_ALL;
    }
    return switch (rawScenario.trim().toUpperCase(Locale.ROOT)) {
      case SCENARIO_IMPORT,
          SCENARIO_EXPORT,
          SCENARIO_PROCESS,
          SCENARIO_DISPATCH,
          SCENARIO_ATOMIC,
          SCENARIO_WORKFLOW -> rawScenario.trim().toUpperCase(Locale.ROOT);
      default -> SCENARIO_ALL;
    };
  }

  private static List<List<Map<String, Object>>> emptySheets() {
    List<List<Map<String, Object>>> sheets = new ArrayList<>(SHEET_COUNT);
    for (int i = 0; i < SHEET_COUNT; i++) {
      sheets.add(new ArrayList<>());
    }
    return sheets;
  }

  private static void putCommonFoundation(List<List<Map<String, Object>>> sheets, String scenario) {
    String queueType =
        switch (scenario) {
          case SCENARIO_IMPORT, SCENARIO_EXPORT, SCENARIO_DISPATCH -> scenario;
          default -> "MIXED";
        };
    sheets
        .get(0)
        .add(row(
            COL_TENANT_ID,
            TENANT,
            COL_QUEUE_CODE,
            QUEUE,
            ConfigPackageExcelSchema.ResourceQueue.COL_QUEUE_NAME,
            "演示队列",
            ConfigPackageExcelSchema.ResourceQueue.COL_QUEUE_TYPE,
            queueType,
            ConfigPackageExcelSchema.ResourceQueue.COL_MAX_RUNNING_JOBS,
            10,
            ConfigPackageExcelSchema.ResourceQueue.COL_MAX_RUNNING_PARTITIONS,
            20,
            ConfigPackageExcelSchema.ResourceQueue.COL_MAX_QPS,
            100,
            COL_WORKER_GROUP,
            scenario.toLowerCase(Locale.ROOT),
            ConfigPackageExcelSchema.ResourceQueue.COL_PRIORITY_POLICY,
            "FIFO",
            ConfigPackageExcelSchema.ResourceQueue.COL_FAIR_SHARE_WEIGHT,
            1,
            COL_ENABLED,
            true,
            COL_DESCRIPTION,
            "示例队列，请按生产容量调整"));
    sheets
        .get(1)
        .add(row(
            COL_TENANT_ID,
            TENANT,
            COL_CALENDAR_CODE,
            CALENDAR,
            ConfigPackageExcelSchema.BusinessCalendar.COL_CALENDAR_NAME,
            "默认业务日历",
            ConfigPackageExcelSchema.BusinessCalendar.COL_TIMEZONE,
            "Asia/Shanghai",
            ConfigPackageExcelSchema.BusinessCalendar.COL_HOLIDAY_ROLL_RULE,
            "SKIP",
            ConfigPackageExcelSchema.BusinessCalendar.COL_CATCH_UP_POLICY,
            "NONE",
            ConfigPackageExcelSchema.BusinessCalendar.COL_CATCH_UP_MAX_DAYS,
            0,
            ConfigPackageExcelSchema.BusinessCalendar.COL_HOLIDAYS,
            "2026-01-01",
            COL_ENABLED,
            true,
            COL_DESCRIPTION,
            "示例日历，请维护真实节假日"));
    sheets
        .get(2)
        .add(row(
            COL_TENANT_ID,
            TENANT,
            COL_WINDOW_CODE,
            WINDOW,
            ConfigPackageExcelSchema.BatchWindow.COL_WINDOW_NAME,
            "全天窗口",
            ConfigPackageExcelSchema.BatchWindow.COL_TIMEZONE,
            "Asia/Shanghai",
            ConfigPackageExcelSchema.BatchWindow.COL_START_TIME,
            "00:00",
            ConfigPackageExcelSchema.BatchWindow.COL_END_TIME,
            "23:59",
            ConfigPackageExcelSchema.BatchWindow.COL_END_STRATEGY,
            "FINISH_RUNNING",
            ConfigPackageExcelSchema.BatchWindow.COL_OUT_OF_WINDOW_ACTION,
            "WAIT",
            ConfigPackageExcelSchema.BatchWindow.COL_ALLOW_CROSS_DAY,
            false,
            COL_ENABLED,
            true,
            COL_DESCRIPTION,
            "示例窗口，请按 SLA 调整"));
  }

  private static void putImport(List<List<Map<String, Object>>> sheets) {
    String job = "JOB_IMPORT_CUSTOMER";
    String tpl = "TPL_IMPORT_CUSTOMER";
    sheets.get(5).add(fileTemplate(tpl, "客户导入模板", SCENARIO_IMPORT, null));
    sheets.get(3).add(job(job, "客户导入", SCENARIO_IMPORT, "{\"templateCode\":\"" + tpl + "\"}"));
    sheets.get(6).add(pipeline(job, "客户导入流水线", SCENARIO_IMPORT));
    sheets.get(7).add(step(job, "RECEIVE", "接收文件", "RECEIVE", 1, "fileReceive", "{}"));
    sheets.get(7).add(step(job, "PARSE", "解析文件", "PARSE", 2, "csvParser", "{}"));
    sheets
        .get(7)
        .add(
            step(job, "LOAD", "加载入库", "LOAD", 3, "jdbcLoad", "{\"templateCode\":\"" + tpl + "\"}"));
  }

  private static void putExport(List<List<Map<String, Object>>> sheets) {
    String job = "JOB_EXPORT_CUSTOMER";
    String tpl = "TPL_EXPORT_CUSTOMER";
    sheets.get(5).add(fileTemplate(tpl, "客户导出模板", SCENARIO_EXPORT, "customer_${batchDate}.csv"));
    sheets.get(3).add(job(job, "客户导出", SCENARIO_EXPORT, "{\"templateCode\":\"" + tpl + "\"}"));
    sheets.get(6).add(pipeline(job, "客户导出流水线", SCENARIO_EXPORT));
    sheets.get(7).add(step(job, "PREPARE", "准备导出", "PREPARE", 1, "exportPrepare", "{}"));
    sheets
        .get(7)
        .add(step(
            job,
            "GENERATE",
            "生成文件",
            "GENERATE",
            2,
            "csvExport",
            "{\"templateCode\":\"" + tpl + "\"}"));
    sheets.get(7).add(step(job, "STORE", "保存文件", "STORE", 3, "objectStore", "{}"));
  }

  private static void putProcess(List<List<Map<String, Object>>> sheets) {
    String job = "JOB_PROCESS_CUSTOMER";
    sheets.get(3).add(job(job, "客户汇总加工", SCENARIO_PROCESS, "{}"));
    sheets.get(6).add(pipeline(job, "客户汇总加工流水线", SCENARIO_PROCESS));
    sheets
        .get(7)
        .add(
            step(
                job,
                "COMPUTE",
                "计算汇总",
                "COMPUTE",
                1,
                "sqlCompute",
                "{\"targetSchema\":\"biz\",\"targetTable\":\"customer_summary\",\"sql\":\"SELECT customer_no, count(*) AS cnt FROM biz.customer_account WHERE tenant_id = :tenantId GROUP BY customer_no\"}"));
    sheets.get(7).add(step(job, "COMMIT", "提交结果", "COMMIT", 2, "jdbcCommit", "{}"));
  }

  private static void putDispatch(List<List<Map<String, Object>>> sheets) {
    String job = "JOB_DISPATCH_CUSTOMER";
    sheets
        .get(4)
        .add(row(
            COL_TENANT_ID,
            TENANT,
            COL_CHANNEL_CODE,
            CHANNEL,
            COL_CHANNEL_NAME,
            "演示 SFTP 渠道",
            COL_CHANNEL_TYPE,
            "SFTP",
            ConfigPackageExcelSchema.FileChannel.COL_TARGET_ENDPOINT,
            "sftp://sftp.example.com:22/outbound",
            COL_AUTH_TYPE,
            "PASSWORD",
            COL_CONFIG_JSON,
            "{\"endpoint\":\"sftp://sftp.example.com:22/outbound\",\"auth\":{\"type\":\"PASSWORD\",\"username\":\"batch_user\"},\"credentials\":{\"passwordRef\":\"kms://channel/demo-sftp\"}}",
            COL_RECEIPT_POLICY,
            "NONE",
            COL_TIMEOUT_SECONDS,
            300,
            COL_ENABLED,
            true));
    sheets.get(3).add(job(job, "客户文件派发", SCENARIO_DISPATCH, "{}"));
    sheets.get(6).add(pipeline(job, "客户文件派发流水线", SCENARIO_DISPATCH));
    sheets
        .get(7)
        .add(step(
            job,
            SCENARIO_DISPATCH,
            "派发文件",
            SCENARIO_DISPATCH,
            1,
            "sftpDispatch",
            "{\"channelCode\":\"" + CHANNEL + "\"}"));
    sheets.get(7).add(step(job, "ACK", "等待回执", "ACK", 2, "dispatchAck", "{}"));
  }

  /** Atomic 是单任务执行器，不创建 pipeline_definition / pipeline_step_definition。 */
  private static void putAtomic(List<List<Map<String, Object>>> sheets) {
    sheets
        .get(3)
        .add(job(
            "JOB_ATOMIC_SQL_CHECK",
            "原子 SQL 健康检查",
            SCENARIO_ATOMIC,
            "{\"taskType\":\"sql\",\"sql\":\"SELECT 1\"}"));
  }

  private static void putWorkflow(List<List<Map<String, Object>>> sheets) {
    putImport(sheets);
    putExport(sheets);
    sheets
        .get(8)
        .add(row(
            COL_TENANT_ID, TENANT,
            COL_WORKFLOW_CODE, "WF_CUSTOMER_EOD",
            COL_WORKFLOW_NAME, "客户日终工作流",
            COL_WORKFLOW_TYPE, "DAG",
            COL_VERSION, VERSION,
            COL_ENABLED, true,
            COL_DESCRIPTION, "先导入后导出的示例 DAG"));
    sheets.get(9).add(workflowNode("START", "开始", "START", null, null, 1));
    sheets.get(9).add(workflowNode("NODE_IMPORT", "客户导入", "JOB", "JOB_IMPORT_CUSTOMER", null, 2));
    sheets.get(9).add(workflowNode("NODE_EXPORT", "客户导出", "JOB", "JOB_EXPORT_CUSTOMER", null, 3));
    sheets.get(9).add(workflowNode("END", "结束", "END", null, null, 4));
    sheets.get(10).add(workflowEdge("START", "NODE_IMPORT"));
    sheets.get(10).add(workflowEdge("NODE_IMPORT", "NODE_EXPORT"));
    sheets.get(10).add(workflowEdge("NODE_EXPORT", "END"));
  }

  private static Map<String, Object> job(
      String code, String name, String type, String defaultParams) {
    return row(
        COL_TENANT_ID,
        TENANT,
        COL_JOB_CODE,
        code,
        COL_JOB_NAME,
        name,
        COL_JOB_TYPE,
        type,
        COL_BIZ_TYPE,
        "CUSTOMER",
        COL_QUEUE_CODE,
        QUEUE,
        COL_WORKER_GROUP,
        type.toLowerCase(Locale.ROOT),
        COL_SCHEDULE_TYPE,
        "MANUAL",
        COL_CALENDAR_CODE,
        CALENDAR,
        COL_WINDOW_CODE,
        WINDOW,
        COL_RETRY_POLICY,
        "NONE",
        COL_RETRY_MAX_COUNT,
        0,
        COL_TIMEOUT_SECONDS,
        3600,
        COL_SHARD_STRATEGY,
        "NONE",
        COL_EXECUTION_MODE,
        "STANDARD",
        COL_PARAM_SCHEMA,
        "{}",
        COL_DEFAULT_PARAMS,
        defaultParams,
        COL_ENABLED,
        true,
        COL_DESCRIPTION,
        "示例作业，请修改编码、名称和参数");
  }

  private static Map<String, Object> fileTemplate(
      String code, String name, String type, String namingRule) {
    return row(
        COL_TENANT_ID,
        TENANT,
        ConfigPackageExcelSchema.FileTemplate.COL_TEMPLATE_CODE,
        code,
        ConfigPackageExcelSchema.FileTemplate.COL_TEMPLATE_NAME,
        name,
        ConfigPackageExcelSchema.FileTemplate.COL_TEMPLATE_TYPE,
        type,
        COL_BIZ_TYPE,
        "CUSTOMER",
        ConfigPackageExcelSchema.FileTemplate.COL_FILE_FORMAT_TYPE,
        "CSV",
        ConfigPackageExcelSchema.FileTemplate.COL_CHARSET,
        "UTF-8",
        ConfigPackageExcelSchema.FileTemplate.COL_LINE_SEPARATOR,
        "\\n",
        ConfigPackageExcelSchema.FileTemplate.COL_DELIMITER,
        ",",
        ConfigPackageExcelSchema.FileTemplate.COL_QUOTE_CHAR,
        "\"",
        ConfigPackageExcelSchema.FileTemplate.COL_ESCAPE_CHAR,
        "\\",
        ConfigPackageExcelSchema.FileTemplate.COL_HEADER_ROWS,
        SCENARIO_IMPORT.equals(type) ? 1 : 0,
        ConfigPackageExcelSchema.FileTemplate.COL_FIELD_MAPPINGS,
        "[{\"name\":\"customerNo\",\"targetColumn\":\"customer_no\",\"type\":\"STRING\",\"required\":true}]",
        ConfigPackageExcelSchema.FileTemplate.COL_VALIDATION_RULE_SET,
        "{}",
        ConfigPackageExcelSchema.FileTemplate.COL_DEFAULT_QUERY_SQL,
        SCENARIO_EXPORT.equals(type)
            ? "SELECT customer_no FROM biz.customer_account WHERE tenant_id = :tenantId"
            : "",
        ConfigPackageExcelSchema.FileTemplate.COL_QUERY_PARAM_SCHEMA,
        "{}",
        ConfigPackageExcelSchema.FileTemplate.COL_STREAMING_ENABLED,
        true,
        ConfigPackageExcelSchema.FileTemplate.COL_PAGE_SIZE,
        1000,
        ConfigPackageExcelSchema.FileTemplate.COL_FETCH_SIZE,
        1000,
        ConfigPackageExcelSchema.FileTemplate.COL_CHUNK_SIZE,
        500,
        ConfigPackageExcelSchema.FileTemplate.COL_LOG_MASKING_ENABLED,
        false,
        ConfigPackageExcelSchema.FileTemplate.COL_CONTENT_ENCRYPTION_ENABLED,
        false,
        ConfigPackageExcelSchema.FileTemplate.COL_NAMING_RULE,
        EmptyChecks.isNull(namingRule) ? "" : namingRule,
        COL_ENABLED,
        true,
        COL_VERSION,
        VERSION,
        COL_DESCRIPTION,
        "示例文件模板，请按真实文件格式修改");
  }

  private static Map<String, Object> pipeline(String job, String name, String type) {
    return row(
        COL_TENANT_ID, TENANT,
        COL_JOB_CODE, job,
        COL_PIPELINE_NAME, name,
        COL_PIPELINE_TYPE, type,
        COL_BIZ_TYPE, "CUSTOMER",
        COL_WORKER_GROUP, type.toLowerCase(Locale.ROOT),
        COL_VERSION, VERSION,
        COL_ENABLED, true,
        COL_DESCRIPTION, "示例流水线");
  }

  private static Map<String, Object> step(
      String job, String code, String name, String stage, int order, String impl, String params) {
    return row(
        COL_JOB_CODE,
        job,
        COL_VERSION,
        VERSION,
        COL_STEP_CODE,
        code,
        COL_STEP_NAME,
        name,
        COL_STAGE_CODE,
        stage,
        ConfigPackageExcelSchema.PipelineStep.COL_STEP_ORDER,
        order,
        ConfigPackageExcelSchema.PipelineStep.COL_IMPL_CODE,
        impl,
        ConfigPackageExcelSchema.PipelineStep.COL_STEP_PARAMS,
        params,
        COL_TIMEOUT_SECONDS,
        600,
        COL_RETRY_POLICY,
        "NONE",
        COL_RETRY_MAX_COUNT,
        0,
        COL_ENABLED,
        true);
  }

  private static Map<String, Object> workflowNode(
      String nodeCode,
      String nodeName,
      String nodeType,
      String relatedJobCode,
      String relatedPipelineCode,
      int order) {
    return row(
        COL_TENANT_ID,
        TENANT,
        COL_WORKFLOW_CODE,
        "WF_CUSTOMER_EOD",
        COL_WORKFLOW_VERSION,
        VERSION,
        COL_NODE_CODE,
        nodeCode,
        COL_NODE_NAME,
        nodeName,
        COL_NODE_TYPE,
        nodeType,
        COL_RELATED_JOB_CODE,
        EmptyChecks.isNull(relatedJobCode) ? "" : relatedJobCode,
        COL_RELATED_PIPELINE_CODE,
        EmptyChecks.isNull(relatedPipelineCode) ? "" : relatedPipelineCode,
        COL_WORKER_GROUP,
        "workflow",
        COL_WINDOW_CODE,
        WINDOW,
        COL_NODE_ORDER,
        order,
        COL_RETRY_POLICY,
        "NONE",
        COL_RETRY_MAX_COUNT,
        0,
        COL_TIMEOUT_SECONDS,
        3600,
        COL_NODE_PARAMS,
        "{}",
        COL_ENABLED,
        true);
  }

  private static Map<String, Object> workflowEdge(String from, String to) {
    return row(
        COL_TENANT_ID, TENANT,
        COL_WORKFLOW_CODE, "WF_CUSTOMER_EOD",
        COL_WORKFLOW_VERSION, VERSION,
        COL_FROM_NODE_CODE, from,
        COL_TO_NODE_CODE, to,
        COL_EDGE_TYPE, "SUCCESS",
        COL_ENABLED, true);
  }

  private static Map<String, Object> row(Object... kv) {
    Map<String, Object> row = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      row.put((String) kv[i], kv[i + 1]);
    }
    return row;
  }
}
