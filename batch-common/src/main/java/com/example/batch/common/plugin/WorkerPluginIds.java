package com.example.batch.common.plugin;

/**
 * Default plugin ids for {@link ImportLoadPlugin} / {@link ExportDataPlugin}.
 * Templates reference these via {@code load_target_ref} / {@code export_data_ref}.
 */
public final class WorkerPluginIds {

    /** Template-driven JDBC INSERT/UPSERT ({@code jdbc_mapped_import} in template). */
    public static final String IMPORT_LOAD_JDBC_MAPPED = "jdbc_mapped";

    /** Template-driven batch + detail SELECT ({@code jdbc_mapped_export} in template). */
    public static final String EXPORT_DATA_JDBC_MAPPED = "jdbc_mapped_export";
    /** Template-driven SQL export ({@code default_query_sql} + {@code query_param_schema.sqlTemplateExport}). */
    public static final String EXPORT_DATA_SQL_TEMPLATE = "sql_template_export";

    private WorkerPluginIds() {
    }
}
