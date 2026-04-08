package com.example.batch.worker.exports.stage.format;

import com.example.batch.common.plugin.ExportDataContext;
import com.example.batch.common.plugin.ExportDataPlugin;
import com.example.batch.worker.exports.domain.ExportJobContext;
import java.nio.file.Path;
import java.util.Map;

/**
 * 不可变参数容器，由 {@link com.example.batch.worker.exports.stage.GenerateStep} 传递给各
 * {@link ExportFormatStrategy} 实现。
 *
 * <p>将参数封装为 record 而非长参数列表，便于新增选项而不破坏现有策略签名。
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
