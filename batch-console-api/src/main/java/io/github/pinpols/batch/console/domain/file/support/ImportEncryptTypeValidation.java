package io.github.pinpols.batch.console.domain.file.support;

import io.github.pinpols.batch.common.enums.FileEncryptType;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.domain.file.mapper.FileTemplateConfigMapper;
import io.github.pinpols.batch.console.domain.file.param.FileTemplateConfigUpsertParam;
import io.github.pinpols.batch.console.domain.file.view.FileTemplateConfigQueryView;
import java.util.Locale;
import java.util.Set;

/**
 * 导入模板加密类型的保存前校验。
 *
 * <p>导入预处理在没有显式 {@code preprocess_pipeline} 时只能按 {@code encrypt_type} 推导 AES 解密；PGP、CUSTOM
 * 会在运行时以 {@code IMPORT_PREPROCESS_ENCRYPT_UNSUPPORTED} 失败。这里把同一条件提前到保存和启用时拒绝，
 * 已配置显式流水线的模板不受影响；未启用的模板骨架也照常允许保存。
 */
public final class ImportEncryptTypeValidation {

  private static final Set<String> IMPLICIT_SUPPORTED =
      Set.of(FileEncryptType.NONE.code(), FileEncryptType.AES.code());

  private ImportEncryptTypeValidation() {}

  /** 返回是否需要查询显式流水线；不需要时调用方可省去一次查询。 */
  public static boolean needsPipelineCheck(
      Boolean enabled, String templateType, String encryptType) {
    return Boolean.TRUE.equals(enabled)
        && "IMPORT".equalsIgnoreCase(templateType)
        && Texts.hasText(encryptType)
        && !IMPLICIT_SUPPORTED.contains(encryptType.trim().toUpperCase(Locale.ROOT));
  }

  public static void validate(
      Boolean enabled, String templateType, String encryptType, boolean hasPreprocessPipeline) {
    if (!needsPipelineCheck(enabled, templateType, encryptType) || hasPreprocessPipeline) {
      return;
    }
    throw BizException.of(
        ResultCode.INVALID_ARGUMENT,
        ResultCode.INVALID_ARGUMENT.detailKey(),
        "import template encrypt_type "
            + encryptType
            + " is not supported without preprocess_pipeline; use NONE or AES");
  }

  /** 校验一次待写入的模板；只有需要时才查询该模板现有的显式流水线。 */
  public static void validate(
      FileTemplateConfigMapper mapper, String tenantId, FileTemplateConfigUpsertParam param) {
    FileTemplateConfigUpsertParam.BasicInfo basicInfo = param.getBasicInfo();
    Boolean enabled = basicInfo.getEnabled();
    String templateType = basicInfo.getTemplateType();
    String encryptType = param.getFormat().getEncryptType();
    if (!needsPipelineCheck(enabled, templateType, encryptType)) {
      return;
    }
    boolean hasPipeline =
        mapper.hasPreprocessPipeline(tenantId, param.getTemplateCode(), basicInfo.getVersion());
    validate(enabled, templateType, encryptType, hasPipeline);
  }

  /** 启用已有模板前校验。 */
  public static void validateEnabling(
      FileTemplateConfigMapper mapper, String tenantId, FileTemplateConfigQueryView existing) {
    String templateType = existing.templateType();
    String encryptType = existing.encryptType();
    if (!needsPipelineCheck(true, templateType, encryptType)) {
      return;
    }
    boolean hasPipeline =
        mapper.hasPreprocessPipeline(tenantId, existing.templateCode(), existing.version());
    validate(true, templateType, encryptType, hasPipeline);
  }
}
