package com.example.batch.common.plugin;

import java.util.List;
import java.util.Map;

/**
 * Import LOAD plugin: turns validated logical rows (maps) into upstream persistence.
 * Add new implementations as Spring beans; register by {@link #id()}.
 */
public interface ImportLoadPlugin {

    /**
     * Stable id, e.g. {@link WorkerPluginIds#IMPORT_LOAD_CUSTOMER_ACCOUNT}.
     */
    String id();

    /**
     * Persist one chunk of rows. Rows match NDJSON lines produced by PARSE/VALIDATE (typically camelCase keys).
     *
     * @return number of rows applied (same spirit as legacy upsert row count)
     */
    int loadChunk(ImportLoadContext context, List<Map<String, Object>> records) throws Exception;
}
