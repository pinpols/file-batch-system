package io.github.pinpols.batch.console.domain.file.web.request;

import io.github.pinpols.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class FileTemplateUpdateRequest {
  @ValidTenantId private String tenantId;

  @Size(max = 256)
  private String templateName;

  @Size(max = 32)
  private String templateType;

  @Size(max = 64)
  private String bizType;

  @Size(max = 32)
  private String fileFormatType;

  @Size(max = 32)
  private String charset;

  @Size(max = 32)
  private String targetCharset;

  private Boolean withBom;

  @Size(max = 16)
  private String lineSeparator;

  @Size(max = 8)
  private String delimiter;

  @Size(max = 8)
  private String quoteChar;

  @Size(max = 8)
  private String escapeChar;

  private Integer recordLength;
  private Integer headerRows;
  private Integer footerRows;
  private String headerTemplateJson;
  private String trailerTemplateJson;

  @Size(max = 32)
  private String checksumType;

  @Size(max = 32)
  private String compressType;

  @Size(max = 32)
  private String encryptType;

  @Size(max = 512)
  private String namingRule;

  private String fieldMappingsJson;
  private String validationRuleSetJson;

  @Size(max = 128)
  private String defaultQueryCode;

  private String defaultQuerySql;
  private String queryParamSchemaJson;
  private Boolean streamingEnabled;
  private Integer pageSize;
  private Integer fetchSize;
  private Integer chunkSize;
  private Boolean previewMaskingEnabled;
  private Boolean errorLineMaskingEnabled;
  private Boolean logMaskingEnabled;
  private Boolean contentEncryptionEnabled;

  @Size(max = 256)
  private String encryptionKeyRef;

  private Boolean downloadRequiresApproval;
  private String maskingRuleSet;
  private Boolean enabled;
  private Integer version;

  @Size(max = 512)
  private String description;

  // V29 引入的 plugin ref 字段。之前 DTO 未暴露 → FE/API 无法更新 → 部分 e2e
  // 种子租户(如 ta)的 IMPORT 模板 load_target_ref 残缺,worker-import 报
  // "jdbc_mapped_import spec missing"。补字段并通过 Service 透传。
  @Size(max = 128)
  private String loadTargetRef;

  @Size(max = 128)
  private String exportDataRef;
}
