package io.github.pinpols.batch.console.infrastructure.config;

import io.github.pinpols.batch.common.utils.Nullables;
import io.github.pinpols.batch.console.domain.file.mapper.FileChannelConfigMapper;
import io.github.pinpols.batch.console.domain.file.mapper.FileTemplateConfigMapper;
import io.github.pinpols.batch.console.domain.file.param.FileChannelConfigUpsertParam;
import io.github.pinpols.batch.console.domain.file.param.FileTemplateConfigUpsertParam;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.FileChannelSpec;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.FileTemplateSpec;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 租户初始化中的文件渠道与文件模板持久化协作者。 */
@Component
@RequiredArgsConstructor
final class TenantFileConfigApplySupport {

  private final FileChannelConfigMapper fileChannelConfigMapper;
  private final FileTemplateConfigMapper fileTemplateConfigMapper;

  Map<String, Object> findChannel(String tenantId, FileChannelSpec spec) {
    return fileChannelConfigMapper.selectByUniqueKey(tenantId, spec.getChannelCode());
  }

  void upsertChannel(String tenantId, FileChannelSpec spec, String operator) {
    FileChannelConfigUpsertParam param = new FileChannelConfigUpsertParam();
    param.setTenantId(tenantId);
    param.setChannelCode(spec.getChannelCode());
    param.setChannelName(spec.getChannelName());
    param.setChannelType(spec.getChannelType());
    param.setTargetEndpoint(spec.getTargetEndpoint());
    param.setAuthType(spec.getAuthType());
    param.setConfigJson(spec.getConfigJson());
    param.setReceiptPolicy(spec.getReceiptPolicy());
    param.setTimeoutSeconds(spec.getTimeoutSeconds());
    param.setEnabled(Nullables.coalesce(spec.getEnabled(), true));
    param.setCreatedBy(operator);
    param.setUpdatedBy(operator);
    fileChannelConfigMapper.upsertFileChannelConfig(param);
  }

  Map<String, Object> findTemplate(String tenantId, FileTemplateSpec spec) {
    return fileTemplateConfigMapper.selectByUniqueKey(
        tenantId, spec.getTemplateCode(), Nullables.coalesce(spec.getVersion(), 1));
  }

  void upsertTemplate(String tenantId, FileTemplateSpec spec, String operator) {
    FileTemplateConfigUpsertParam param = new FileTemplateConfigUpsertParam();
    param.setTenantId(tenantId);
    param.setTemplateCode(spec.getTemplateCode());

    FileTemplateConfigUpsertParam.BasicInfo basicInfo =
        new FileTemplateConfigUpsertParam.BasicInfo();
    basicInfo.setTemplateName(spec.getTemplateName());
    basicInfo.setTemplateType(spec.getTemplateType());
    basicInfo.setBizType(spec.getBizType());
    basicInfo.setEnabled(Nullables.coalesce(spec.getEnabled(), true));
    basicInfo.setVersion(Nullables.coalesce(spec.getVersion(), 1));
    basicInfo.setDescription(spec.getDescription());
    param.setBasicInfo(basicInfo);

    FileTemplateConfigUpsertParam.FormatOptions format =
        new FileTemplateConfigUpsertParam.FormatOptions();
    format.setFileFormatType(spec.getFileFormatType());
    format.setCharset(spec.getCharset());
    format.setTargetCharset(spec.getTargetCharset());
    format.setWithBom(spec.getWithBom());
    format.setLineSeparator(spec.getLineSeparator());
    format.setDelimiter(spec.getDelimiter());
    format.setQuoteChar(spec.getQuoteChar());
    format.setEscapeChar(spec.getEscapeChar());
    format.setRecordLength(spec.getRecordLength());
    format.setHeaderRows(spec.getHeaderRows());
    format.setFooterRows(spec.getFooterRows());
    format.setHeaderTemplateJson(spec.getHeaderTemplateJson());
    format.setTrailerTemplateJson(spec.getTrailerTemplateJson());
    format.setChecksumType(spec.getChecksumType());
    format.setCompressType(spec.getCompressType());
    format.setEncryptType(spec.getEncryptType());
    format.setNamingRule(spec.getNamingRule());
    format.setFieldMappingsJson(spec.getFieldMappingsJson());
    format.setValidationRuleSetJson(spec.getValidationRuleSetJson());
    param.setFormat(format);

    FileTemplateConfigUpsertParam.QueryOptions query =
        new FileTemplateConfigUpsertParam.QueryOptions();
    query.setDefaultQueryCode(spec.getDefaultQueryCode());
    query.setDefaultQuerySql(spec.getDefaultQuerySql());
    query.setQueryParamSchemaJson(spec.getQueryParamSchemaJson());
    param.setQuery(query);

    FileTemplateConfigUpsertParam.RuntimeOptions runtime =
        new FileTemplateConfigUpsertParam.RuntimeOptions();
    runtime.setStreamingEnabled(spec.getStreamingEnabled());
    runtime.setPageSize(spec.getPageSize());
    runtime.setFetchSize(spec.getFetchSize());
    runtime.setChunkSize(spec.getChunkSize());
    param.setRuntime(runtime);

    FileTemplateConfigUpsertParam.SecurityOptions security =
        new FileTemplateConfigUpsertParam.SecurityOptions();
    security.setPreviewMaskingEnabled(spec.getPreviewMaskingEnabled());
    security.setErrorLineMaskingEnabled(spec.getErrorLineMaskingEnabled());
    security.setLogMaskingEnabled(spec.getLogMaskingEnabled());
    security.setContentEncryptionEnabled(spec.getContentEncryptionEnabled());
    security.setEncryptionKeyRef(spec.getEncryptionKeyRef());
    security.setDownloadRequiresApproval(spec.getDownloadRequiresApproval());
    security.setMaskingRuleSet(spec.getMaskingRuleSet());
    param.setSecurity(security);

    FileTemplateConfigUpsertParam.AuditOptions audit =
        new FileTemplateConfigUpsertParam.AuditOptions();
    audit.setCreatedBy(operator);
    audit.setUpdatedBy(operator);
    param.setAudit(audit);
    fileTemplateConfigMapper.upsertFileTemplateConfig(param);
  }
}
