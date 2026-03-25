package com.example.batch.worker.exports.stage.format;

import com.example.batch.common.plugin.ExportDataContext;
import com.example.batch.common.plugin.ExportDataPlugin;
import com.example.batch.worker.exports.domain.ExportJobContext;
import java.nio.file.Path;
import java.util.Map;

/**
 * Immutable parameter bag passed from {@link com.example.batch.worker.exports.stage.GenerateStep}
 * to each {@link ExportFormatStrategy} implementation.
 *
 * <p>Encapsulating these parameters in a record instead of a long parameter list makes
 * it easy to add new options without breaking existing strategy signatures.
 */
public record ExportFormatContext(
        Map<String, Object> batch,
        Object batchId,
        int pageSize,
        int chunkSize,
        Path generatedFile,
        ExportJobContext jobContext,
        ExportDataPlugin dataPlugin,
        ExportDataContext dataCtx
) {
}
