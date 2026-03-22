package com.example.batch.common.plugin;

/**
 * Default plugin ids for {@link ImportLoadPlugin} / {@link ExportDataPlugin}.
 * Templates reference these via {@code load_target_ref} / {@code export_data_ref}.
 */
public final class WorkerPluginIds {

    public static final String IMPORT_LOAD_CUSTOMER_ACCOUNT = "customer_account";
    /** Template-driven JDBC INSERT/UPSERT ({@code jdbc_mapped_import} in template). */
    public static final String IMPORT_LOAD_JDBC_MAPPED = "jdbc_mapped";

    public static final String EXPORT_DATA_SETTLEMENT = "settlement";
    /** Template-driven batch + detail SELECT ({@code jdbc_mapped_export} in template). */
    public static final String EXPORT_DATA_JDBC_MAPPED = "jdbc_mapped_export";

    private WorkerPluginIds() {
    }
}
