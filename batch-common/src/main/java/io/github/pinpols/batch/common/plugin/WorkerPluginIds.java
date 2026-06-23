package io.github.pinpols.batch.common.plugin;

/**
 * {@link ImportLoadPlugin} / {@link ExportDataPlugin} 的默认插件标识。 模板通过 {@code load_target_ref} /
 * {@code export_data_ref} 引用这些标识。
 */
public final class WorkerPluginIds {

  /** 基于模板驱动的 JDBC INSERT/UPSERT（模板中为 {@code jdbc_mapped_import}）。 */
  public static final String IMPORT_LOAD_JDBC_MAPPED = "jdbc_mapped";

  /** 基于模板驱动的批量 + 明细 SELECT（模板中为 {@code jdbc_mapped_export}）。 */
  public static final String EXPORT_DATA_JDBC_MAPPED = "jdbc_mapped_export";

  /** 基于模板驱动的 SQL 导出（{@code default_query_sql} + {@code query_param_schema.sqlTemplateExport}）。 */
  public static final String EXPORT_DATA_SQL_TEMPLATE = "sql_template_export";

  private WorkerPluginIds() {}
}
