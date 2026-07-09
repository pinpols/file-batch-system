package io.github.pinpols.batch.worker.exports.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** SQL 模板导出安全配置，绑定前缀 {@code batch.worker.export.sql-template}。 */
@Data
@ConfigurationProperties(prefix = "batch.worker.export.sql-template")
public class SqlTemplateExportSecurityProperties {

  /** 每次查询的语句超时时间（秒）。 */
  private int queryTimeoutSeconds = 30;

  /** 单页最大行数上限，防止误配大页造成内存压力。 */
  private int maxPageSize = 5000;

  /** 模板 SQL 中必须包含的具名参数，用于强制租户隔离。 */
  private List<String> requiredParams = new ArrayList<>(List.of("tenantId", "batchNo"));

  /**
   * S-1.10：模板 SQL 允许使用的额外具名参数白名单（除 required + 引擎保留 __cursor / __limit 之外）。 默认包含常见业务占位符 {@code
   * bizDate}（业务日期过滤常用）与 {@code region}（地区过滤，per-run + 字典校验，PrepareStep 解析后透传绑定）； 其余扩展参数仍需在 yml
   * 显式列出。 注：{@code tenantId} / {@code batchNo} 已通过 {@link #requiredParams} 强制，这里不必重复。
   */
  private List<String> allowedExtraParams = new ArrayList<>(List.of("bizDate", "region"));

  /** 允许引用的 schema 白名单，空列表表示不限制。 示例：["biz", "ref"] — 引用其他 schema（如 pg_catalog）的表将被拒绝。 */
  private List<String> allowedSchemas = new ArrayList<>();

  /** 在解析阶段拒绝 {@code SELECT *} / {@code SELECT table.*}。 */
  private boolean forbidSelectStar = true;

  /** 在首页查询前对基础 SQL 执行 {@code EXPLAIN (FORMAT JSON)} 预检。 默认关闭；在需要检查执行计划代价或预估行数的环境中可开启。 */
  private boolean explainCheckEnabled = false;

  /** EXPLAIN 预估最大行数上限，&lt;= 0 表示不限制。 仅在 {@link #explainCheckEnabled} 为 true 时生效。 */
  private long maxEstimatedRows = 5_000_000L;

  /** EXPLAIN 预估最大总代价上限，&lt;= 0 表示不限制。 仅在 {@link #explainCheckEnabled} 为 true 时生效。 */
  private double maxPlanCost = -1;

  /**
   * 禁止在 SQL 中调用的 PG 函数(大小写不敏感子串匹配 + 边界检查)。覆盖跨库 dblink / 系统级 pg_terminate_backend / 文件系统
   * pg_read_server_files / 任意命令 copy_from_program 等。
   */
  private List<String> forbiddenFunctions =
      new ArrayList<>(
          List.of(
              "dblink",
              "pg_terminate_backend",
              "pg_cancel_backend",
              "pg_read_file",
              "pg_read_server_files",
              "pg_read_binary_file",
              "pg_ls_dir",
              "copy_from_program",
              "lo_import",
              "lo_export",
              "pg_sleep"));
}
