package io.github.pinpols.batch.console.domain.file.application.contract.response;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.SecretMasking;
import io.github.pinpols.batch.console.domain.file.view.FileChannelConfigQueryView;
import io.github.pinpols.batch.console.domain.file.view.FileTemplateConfigQueryView;

/** 将文件配置的具名查询视图转换为稳定的 Console API record。 */
public final class ConsoleFileProjectionMapper {

  private ConsoleFileProjectionMapper() {}

  public static ConsoleFileChannelResponse channelView(FileChannelConfigQueryView row) {
    if (EmptyChecks.isNull(row)) {
      return null;
    }
    String configJson = safeMaskedConfigJson(row.configJson());
    return ConsoleFileChannelResponse.builder()
        .id(row.id())
        .tenantId(row.tenantId())
        .channelCode(row.channelCode())
        .channelName(row.channelName())
        .channelType(row.channelType())
        .targetEndpoint(SecretMasking.maskEndpoint(row.targetEndpoint()))
        .authType(row.authType())
        .configJson(configJson)
        .receiptPolicy(row.receiptPolicy())
        .timeoutSeconds(row.timeoutSeconds())
        .enabled(row.enabled())
        .createdAt(row.createdAt())
        .updatedAt(row.updatedAt())
        .build();
  }

  private static String safeMaskedConfigJson(Object value) {
    try {
      String configJson = value == null ? null : value.toString();
      return SecretMasking.maskSensitiveJson(configJson);
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }

  public static ConsoleFileTemplateResponse templateView(FileTemplateConfigQueryView row) {
    if (row == null) {
      return null;
    }
    return ConsoleFileTemplateResponse.builder()
        .id(row.id())
        .tenantId(row.tenantId())
        .templateCode(row.templateCode())
        .templateName(row.templateName())
        .templateType(row.templateType())
        .bizType(row.bizType())
        .fileFormatType(row.fileFormatType())
        .charset(row.charset())
        .targetCharset(row.targetCharset())
        .withBom(row.withBom())
        .lineSeparator(row.lineSeparator())
        .delimiter(row.delimiter())
        .quoteChar(row.quoteChar())
        .escapeChar(row.escapeChar())
        .recordLength(row.recordLength())
        .headerRows(row.headerRows())
        .footerRows(row.footerRows())
        .headerTemplate(stringValue(row.headerTemplate()))
        .trailerTemplate(stringValue(row.trailerTemplate()))
        .checksumType(row.checksumType())
        .compressType(row.compressType())
        .encryptType(row.encryptType())
        .namingRule(row.namingRule())
        .fieldMappings(stringValue(row.fieldMappings()))
        .validationRuleSet(stringValue(row.validationRuleSet()))
        .defaultQueryCode(row.defaultQueryCode())
        .defaultQuerySql(row.defaultQuerySql())
        .queryParamSchema(stringValue(row.queryParamSchema()))
        .streamingEnabled(row.streamingEnabled())
        .pageSize(row.pageSize())
        .fetchSize(row.fetchSize())
        .chunkSize(row.chunkSize())
        .previewMaskingEnabled(row.previewMaskingEnabled())
        .errorLineMaskingEnabled(row.errorLineMaskingEnabled())
        .logMaskingEnabled(row.logMaskingEnabled())
        .contentEncryptionEnabled(row.contentEncryptionEnabled())
        .encryptionKeyRef(row.encryptionKeyRef())
        .downloadRequiresApproval(row.downloadRequiresApproval())
        .maskingRuleSet(row.maskingRuleSet())
        .enabled(row.enabled())
        .version(row.version())
        .description(row.description())
        .createdBy(row.createdBy())
        .updatedBy(row.updatedBy())
        .createdAt(row.createdAt())
        .updatedAt(row.updatedAt())
        .build();
  }

  private static String stringValue(Object value) {
    return value == null ? null : value.toString();
  }
}
