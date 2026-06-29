package io.github.pinpols.batch.console.domain.file.web.request;

import io.github.pinpols.batch.common.validation.ValidTenantId;
import io.github.pinpols.batch.console.domain.file.application.FileTemplateMappingDraftCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/** 文件模板字段映射草案请求：把向导式字段输入转换成现有 template JSON 配置。 */
@Data
public class FileTemplateMappingDraftRequest {

  @ValidTenantId private String tenantId;

  /** IMPORT / EXPORT；缺省按 IMPORT 生成。 */
  @Size(max = 16)
  private String direction;

  @Size(max = 64)
  private String schemaName;

  @Size(max = 128)
  private String tableName;

  @Size(max = 128)
  private String tenantColumn;

  private List<String> conflictColumns = new ArrayList<>();
  private Boolean standardAuditBindings;
  private String defaultQuerySql;

  private List<@Valid Field> fields = new ArrayList<>();

  public FileTemplateMappingDraftCommand toCommand() {
    return new FileTemplateMappingDraftCommand(
        tenantId,
        direction,
        schemaName,
        tableName,
        tenantColumn,
        conflictColumns,
        standardAuditBindings,
        defaultQuerySql,
        fields == null ? List.of() : fields.stream().map(Field::toCommandOrNull).toList());
  }

  @Data
  public static class Field {
    @Size(max = 128)
    private String sourceColumn;

    @Size(max = 128)
    private String targetColumn;

    @Size(max = 128)
    private String header;

    @Size(max = 32)
    private String type;

    private Boolean required;
    private Boolean persist;

    @Size(max = 64)
    private String format;

    private static FileTemplateMappingDraftCommand.Field toCommandOrNull(Field field) {
      return field == null ? null : field.toCommand();
    }

    private FileTemplateMappingDraftCommand.Field toCommand() {
      return new FileTemplateMappingDraftCommand.Field(
          sourceColumn, targetColumn, header, type, required, persist, format);
    }
  }
}
