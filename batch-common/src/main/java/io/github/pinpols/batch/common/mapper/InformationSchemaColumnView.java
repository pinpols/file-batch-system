package io.github.pinpols.batch.common.mapper;

/** information_schema.columns 的固定列元数据投影。 */
public record InformationSchemaColumnView(
    String columnName,
    String dataType,
    String isNullable,
    String columnDefault,
    String characterMaximumLength,
    String numericPrecision,
    String numericScale) {}
