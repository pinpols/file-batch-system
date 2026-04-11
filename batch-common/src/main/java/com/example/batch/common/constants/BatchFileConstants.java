package com.example.batch.common.constants;

import java.util.UUID;

public final class BatchFileConstants {

    public static final String IMPORT_STAGE_PREFIX = "batch-import-";
    public static final String EXPORT_STAGE_PREFIX = "batch-export-";
    public static final String TEMP_OBJECT_PREFIX = "tmp/";
    public static final String OUTBOUND_OBJECT_PREFIX = "outbound/";
    public static final String FILE_PART_SUFFIX = ".part";
    public static final String BIN_SUFFIX = ".bin";
    public static final String NDJSON_SUFFIX = ".ndjson";
    public static final String CSV_SUFFIX = ".csv";
    public static final String XLSX_SUFFIX = ".xlsx";
    public static final String TXT_SUFFIX = ".txt";
    public static final String JSON_SUFFIX = ".json";
    public static final String DEFAULT_FILE_NAME = "file.bin";

    public static final String CONTENT_TYPE_JSON = "application/json";
    public static final String CONTENT_TYPE_OCTET_STREAM = "application/octet-stream";
    public static final String CONTENT_TYPE_CSV = "text/csv";
    public static final String CONTENT_TYPE_EXCEL =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    public static final String CONTENT_TYPE_XML = "application/xml";
    public static final String CONTENT_TYPE_TEXT_UTF8 = "text/plain; charset=UTF-8";
    public static final String CONTENT_TYPE_NDJSON = "application/x-ndjson";
    public static final String ERROR_OUTPUT_PREFIX = "errors/";
    public static final String EXPORT_OBJECT_PREFIX = "exports/";
    public static final String ENCRYPTED_EXPORT_PREFIX = "batch-export-encrypted-";

    public static final String HEALTH_PROBE_PREFIX = ".batch-health-";
    public static final String HEALTH_PROBE_SUFFIX = ".probe";

    private BatchFileConstants() {}

    public static String importStagePrefix(String fileId, String workerId, String phase) {
        return IMPORT_STAGE_PREFIX
                + sanitize(fileId)
                + "-"
                + sanitize(workerId)
                + "-"
                + sanitize(phase)
                + "-";
    }

    public static String validatedStagePrefix(String fileId, String workerId) {
        return IMPORT_STAGE_PREFIX + sanitize(fileId) + "-" + sanitize(workerId) + "-validated-";
    }

    public static String exportStagePrefix(String tenantId, String batchNo) {
        return EXPORT_STAGE_PREFIX + sanitize(tenantId) + "-" + sanitize(batchNo) + "-";
    }

    public static String tempObjectName(String tenantId, String bizDate, String fileName) {
        return TEMP_OBJECT_PREFIX
                + sanitize(tenantId)
                + "/"
                + sanitize(bizDate)
                + "/"
                + sanitize(fileName)
                + FILE_PART_SUFFIX;
    }

    public static String outboundObjectName(
            String bizType, String bizDate, String batchNo, String version, String fileName) {
        return OUTBOUND_OBJECT_PREFIX
                + sanitize(bizType)
                + "/"
                + sanitize(bizDate)
                + "/"
                + sanitize(batchNo)
                + "/"
                + sanitize(version)
                + "/"
                + sanitize(fileName);
    }

    public static String newHealthProbeName() {
        return HEALTH_PROBE_PREFIX + UUID.randomUUID() + HEALTH_PROBE_SUFFIX;
    }

    public static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
