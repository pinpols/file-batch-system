package com.example.batch.worker.exports.config;

import com.example.batch.worker.core.config.WorkerConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 导出 Worker 配置属性，绑定前缀 {@code batch.worker.export}。
 */
@ConfigurationProperties(prefix = "batch.worker.export")
public record ExportWorkerConfiguration(
        String workerCode,
        String workerType,
        String tenantId,
        Long heartbeatIntervalMillis,
        String topic,
        String consumerGroupId,
        long maxExportRows,
        FileProcessing fileProcessing
) implements WorkerConfiguration {
    private static final int DEFAULT_PAGE_SIZE = 1000;
    private static final int DEFAULT_FETCH_SIZE = 1000;
    private static final int DEFAULT_CHUNK_SIZE = 500;
    private static final long DEFAULT_MAX_EXPORT_ROWS = 500_000L;

    /**
     * 返回有效的最大导出行数，未配置或非正值时使用默认值。
     */
    public long effectiveMaxExportRows() {
        return maxExportRows <= 0 ? DEFAULT_MAX_EXPORT_ROWS : maxExportRows;
    }

    /**
     * 返回是否启用流式处理。
     */
    public boolean streamingEnabled() {
        return fileProcessing != null && fileProcessing.streamingEnabled();
    }

    /**
     * 返回有效的分页大小，未配置或非正值时使用默认值。
     */
    public int pageSize() {
        return fileProcessing == null || fileProcessing.pageSize() <= 0 ? DEFAULT_PAGE_SIZE : fileProcessing.pageSize();
    }

    /**
     * 返回有效的 JDBC fetchSize，未配置或非正值时使用默认值。
     */
    public int fetchSize() {
        return fileProcessing == null || fileProcessing.fetchSize() <= 0 ? DEFAULT_FETCH_SIZE : fileProcessing.fetchSize();
    }

    /**
     * 返回有效的写入块大小，未配置或非正值时使用默认值。
     */
    public int chunkSize() {
        return fileProcessing == null || fileProcessing.chunkSize() <= 0 ? DEFAULT_CHUNK_SIZE : fileProcessing.chunkSize();
    }

    public record FileProcessing(
            boolean streamingEnabled,
            int pageSize,
            int fetchSize,
            int chunkSize
    ) {
    }
}
