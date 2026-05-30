package com.example.batch.console.domain.file.web.request;

import com.example.batch.common.validation.ValidResourceCode;
import com.example.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class FileTemplateCreateRequest {
  @ValidTenantId private String tenantId;

  @ValidResourceCode private String templateCode;

  @Size(max = 256)
  private String templateName;

  @NotBlank
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
}
