package com.example.batch.common.plugin;

import java.util.List;
import java.util.Map;

/**
 * Export GENERATE plugin: loads batch header + detail pages for file generation.
 */
public interface ExportDataPlugin {

    record DetailPage(List<Map<String, Object>> rows, Object nextCursor) {
        public static DetailPage empty() {
            return new DetailPage(List.of(), null);
        }
    }

    record DelimitedColumn(String header, String source) {
    }

    String id();

    /**
     * Load batch row (e.g. settlement batch). Empty map means not found.
     */
    Map<String, Object> loadBatch(ExportDataContext context) throws Exception;

    /**
     * Load one detail page using plugin-defined cursor semantics.
     * {@code nextCursor == null} means no more data.
     */
    DetailPage loadDetailPage(ExportDataContext context, Long batchId, int pageSize, Object cursor) throws Exception;

    /**
     * Optional plugin-owned delimited layout. Template-level config may still override this.
     */
    default List<DelimitedColumn> describeDelimitedColumns(ExportDataContext context, Map<String, Object> batch) {
        return List.of();
    }

    /**
     * Optional plugin-owned fixed-width layout. Defaults to the delimited layout.
     */
    default List<DelimitedColumn> describeFixedWidthColumns(ExportDataContext context, Map<String, Object> batch) {
        return describeDelimitedColumns(context, batch);
    }
}
