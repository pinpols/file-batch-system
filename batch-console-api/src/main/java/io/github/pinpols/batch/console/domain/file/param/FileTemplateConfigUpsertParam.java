package io.github.pinpols.batch.console.domain.file.param;

import lombok.Data;

@Data
public class FileTemplateConfigUpsertParam {

  private String tenantId;
  private String templateCode;
  private BasicInfo basicInfo;
  private FormatOptions format;
  private QueryOptions query;
  private RuntimeOptions runtime;
  private SecurityOptions security;
  private AuditOptions audit;

  @Data
  public static class BasicInfo {
    private String templateName;
    private String templateType;
    private String bizType;
    private Boolean enabled;
    private Integer version;
    private String description;
  }

  @Data
  public static class FormatOptions {
    private String fileFormatType;
    private String charset;
    private String targetCharset;
    private Boolean withBom;
    private String lineSeparator;
    private String delimiter;
    private String quoteChar;
    private String escapeChar;
    private Integer recordLength;
    private Integer headerRows;
    private Integer footerRows;
    private String headerTemplateJson;
    private String trailerTemplateJson;
    private String checksumType;
    private String compressType;
    private String encryptType;
    private String namingRule;
    private String fieldMappingsJson;
    private String validationRuleSetJson;
  }

  @Data
  public static class QueryOptions {
    private String defaultQueryCode;
    private String defaultQuerySql;
    private String queryParamSchemaJson;
  }

  @Data
  public static class RuntimeOptions {
    private Boolean streamingEnabled;
    private Integer pageSize;
    private Integer fetchSize;
    private Integer chunkSize;
  }

  @Data
  public static class SecurityOptions {
    private Boolean previewMaskingEnabled;
    private Boolean errorLineMaskingEnabled;
    private Boolean logMaskingEnabled;
    private Boolean contentEncryptionEnabled;
    private String encryptionKeyRef;
    private Boolean downloadRequiresApproval;
    private String maskingRuleSet;
  }

  @Data
  public static class AuditOptions {
    private String createdBy;
    private String updatedBy;
  }
}
