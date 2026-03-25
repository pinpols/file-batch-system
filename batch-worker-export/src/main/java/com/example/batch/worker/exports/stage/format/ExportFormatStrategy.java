package com.example.batch.worker.exports.stage.format;

/**
 * Strategy for generating an export file in a specific format.
 *
 * <p>Implementations registered as Spring beans are collected by
 * {@link ExportFormatStrategyRegistry} and selected at runtime by
 * {@code fileFormatType}. Adding a new format only requires a new implementation
 * — {@link com.example.batch.worker.exports.stage.GenerateStep} is not modified.
 */
public interface ExportFormatStrategy {

    /**
     * The format type token this strategy handles (e.g. {@code "JSON"}, {@code "DELIMITED"},
     * {@code "EXCEL"}, {@code "FIXED_WIDTH"}).  Case-insensitive matching is applied by
     * the registry.
     */
    String formatType();

    /**
     * Generates the export file described in {@code ctx} and returns the number of data
     * records written.
     *
     * @throws Exception if generation fails for any reason
     */
    long generate(ExportFormatContext ctx) throws Exception;
}
