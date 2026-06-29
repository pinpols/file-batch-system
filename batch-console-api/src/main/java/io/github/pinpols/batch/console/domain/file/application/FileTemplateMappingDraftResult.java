package io.github.pinpols.batch.console.domain.file.application;

import java.util.List;

/** 文件模板字段映射草案结果：应用层只返回生成好的 JSON 字符串和提示。 */
public record FileTemplateMappingDraftResult(
    String direction,
    String fieldMappingsJson,
    String queryParamSchemaJson,
    String defaultQuerySql,
    List<String> warnings) {}
