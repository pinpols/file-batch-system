package com.example.batch.worker.exports.stage.format;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Registry that collects all {@link ExportFormatStrategy} beans and allows lookup by
 * {@link ExportFormatStrategy#formatType()} (case-insensitive).
 *
 * <p>Mirrors the existing {@code ImportLoadPluginRegistry} / {@code ExportDataPluginRegistry}
 * pattern already used in the codebase.
 */
@Component
public class ExportFormatStrategyRegistry {

    private final Map<String, ExportFormatStrategy> strategiesByType;

    public ExportFormatStrategyRegistry(List<ExportFormatStrategy> strategies) {
        this.strategiesByType = strategies.stream()
                .collect(Collectors.toUnmodifiableMap(
                        s -> s.formatType().toUpperCase(),
                        Function.identity()
                ));
    }

    /**
     * Returns the strategy for the given format type, using {@code "JSON"} as the default
     * when the format type is blank or unrecognized.
     */
    public ExportFormatStrategy resolve(String fileFormatType) {
        if (fileFormatType == null || fileFormatType.isBlank()) {
            return require("JSON");
        }
        ExportFormatStrategy strategy = strategiesByType.get(fileFormatType.trim().toUpperCase());
        return strategy != null ? strategy : require("JSON");
    }

    /**
     * Returns the strategy for the given format type, or throws if not registered.
     */
    public ExportFormatStrategy require(String fileFormatType) {
        ExportFormatStrategy strategy = strategiesByType.get(fileFormatType == null ? "" : fileFormatType.trim().toUpperCase());
        if (strategy == null) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "unsupported export format type: " + fileFormatType);
        }
        return strategy;
    }
}
