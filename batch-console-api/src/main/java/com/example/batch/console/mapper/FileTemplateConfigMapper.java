package com.example.batch.console.mapper;

import com.example.batch.common.model.PageRequest;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface FileTemplateConfigMapper {

    List<Map<String, Object>> selectByQuery(@Param("tenantId") String tenantId,
                                            @Param("templateCode") String templateCode,
                                            @Param("templateType") String templateType,
                                            @Param("enabled") Boolean enabled,
                                            @Param("pageRequest") PageRequest pageRequest);

    long countByQuery(@Param("tenantId") String tenantId,
                      @Param("templateCode") String templateCode,
                      @Param("templateType") String templateType,
                      @Param("enabled") Boolean enabled);

    Map<String, Object> selectSecurityFlagsByTemplateCode(@Param("tenantId") String tenantId,
                                                          @Param("templateCode") String templateCode);

    Map<String, Object> selectByUniqueKey(@Param("tenantId") String tenantId,
                                          @Param("templateCode") String templateCode,
                                          @Param("version") Integer version);

    int upsertFileTemplateConfig(@Param("tenantId") String tenantId,
                                 @Param("templateCode") String templateCode,
                                 @Param("templateName") String templateName,
                                 @Param("templateType") String templateType,
                                 @Param("bizType") String bizType,
                                 @Param("fileFormatType") String fileFormatType,
                                 @Param("charset") String charset,
                                 @Param("targetCharset") String targetCharset,
                                 @Param("withBom") Boolean withBom,
                                 @Param("lineSeparator") String lineSeparator,
                                 @Param("delimiter") String delimiter,
                                 @Param("quoteChar") String quoteChar,
                                 @Param("escapeChar") String escapeChar,
                                 @Param("recordLength") Integer recordLength,
                                 @Param("headerRows") Integer headerRows,
                                 @Param("footerRows") Integer footerRows,
                                 @Param("headerTemplateJson") String headerTemplateJson,
                                 @Param("trailerTemplateJson") String trailerTemplateJson,
                                 @Param("checksumType") String checksumType,
                                 @Param("compressType") String compressType,
                                 @Param("encryptType") String encryptType,
                                 @Param("namingRule") String namingRule,
                                 @Param("fieldMappingsJson") String fieldMappingsJson,
                                 @Param("validationRuleSetJson") String validationRuleSetJson,
                                 @Param("defaultQueryCode") String defaultQueryCode,
                                 @Param("defaultQuerySql") String defaultQuerySql,
                                 @Param("queryParamSchemaJson") String queryParamSchemaJson,
                                 @Param("streamingEnabled") Boolean streamingEnabled,
                                 @Param("pageSize") Integer pageSize,
                                 @Param("fetchSize") Integer fetchSize,
                                 @Param("chunkSize") Integer chunkSize,
                                 @Param("previewMaskingEnabled") Boolean previewMaskingEnabled,
                                 @Param("errorLineMaskingEnabled") Boolean errorLineMaskingEnabled,
                                 @Param("logMaskingEnabled") Boolean logMaskingEnabled,
                                 @Param("contentEncryptionEnabled") Boolean contentEncryptionEnabled,
                                 @Param("encryptionKeyRef") String encryptionKeyRef,
                                 @Param("downloadRequiresApproval") Boolean downloadRequiresApproval,
                                 @Param("maskingRuleSet") String maskingRuleSet,
                                 @Param("enabled") Boolean enabled,
                                 @Param("version") Integer version,
                                 @Param("description") String description,
                                 @Param("createdBy") String createdBy,
                                 @Param("updatedBy") String updatedBy);
}
