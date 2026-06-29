package io.github.pinpols.batch.console.domain.file.web.response;

import io.github.pinpols.batch.console.domain.file.application.FileTemplateMappingDraftResult;
import java.util.List;

/** 文件模板字段映射草案：前端可直接带入 FileTemplateCreate/UpdateRequest。 */
public record FileTemplateMappingDraftResponse(
    String direction,
    String fieldMappingsJson,
    String queryParamSchemaJson,
    String defaultQuerySql,
    List<String> warnings) {

  public static FileTemplateMappingDraftResponse from(FileTemplateMappingDraftResult result) {
    return new FileTemplateMappingDraftResponse(
        result.direction(),
        result.fieldMappingsJson(),
        result.queryParamSchemaJson(),
        result.defaultQuerySql(),
        result.warnings());
  }
}
