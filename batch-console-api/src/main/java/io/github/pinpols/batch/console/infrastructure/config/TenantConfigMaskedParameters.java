package io.github.pinpols.batch.console.infrastructure.config;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.SecretMasking;

/** 初始化 Job 与 Workflow 参数时共用的 JSON 掩码恢复及未解析掩码拒绝规则。 */
final class TenantConfigMaskedParameters {

  private TenantConfigMaskedParameters() {}

  /** Job 与 Workflow 参数均沿用单条维护和 Excel 导入的规则：掩码只恢复目标同位置值。 */
  static String resolve(String submitted, String stored) {
    String resolved = SecretMasking.preserveMaskedJson(submitted, stored);
    if (SecretMasking.containsMaskedSensitiveValue(resolved)) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.config.masked_secret_requires_value");
    }
    return resolved;
  }
}
