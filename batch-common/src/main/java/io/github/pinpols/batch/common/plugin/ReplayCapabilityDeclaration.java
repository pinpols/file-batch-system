package io.github.pinpols.batch.common.plugin;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.util.List;

/** 由 Worker 按冻结插件配置解析、默认拒绝的重放合同。 */
public record ReplayCapabilityDeclaration(
    String pluginId,
    String pluginVersion,
    List<String> externalEffectCategories,
    IdempotencyCapability idempotencyCapability,
    String idempotencyKeyScope,
    String recoveryProtocolId,
    int contractVersion) {

  public static final int CURRENT_CONTRACT_VERSION = 1;
  private static final String UNKNOWN = "UNKNOWN";

  public ReplayCapabilityDeclaration {
    if (EmptyChecks.isBlank(pluginId)) {
      throw new IllegalArgumentException("pluginId must not be blank");
    }
    if (EmptyChecks.isBlank(pluginVersion)) {
      throw new IllegalArgumentException("pluginVersion must not be blank");
    }
    if (EmptyChecks.isEmpty(externalEffectCategories)) {
      throw new IllegalArgumentException("externalEffectCategories must not be empty");
    }
    if (externalEffectCategories.stream().anyMatch(EmptyChecks::isBlank)) {
      throw new IllegalArgumentException("externalEffectCategories must not contain blanks");
    }
    if (EmptyChecks.isNull(idempotencyCapability)) {
      throw new IllegalArgumentException("idempotencyCapability must not be null");
    }
    if (EmptyChecks.isBlank(idempotencyKeyScope)) {
      throw new IllegalArgumentException("idempotencyKeyScope must not be blank");
    }
    if (EmptyChecks.isBlank(recoveryProtocolId)) {
      throw new IllegalArgumentException("recoveryProtocolId must not be blank");
    }
    if (contractVersion != CURRENT_CONTRACT_VERSION) {
      throw new IllegalArgumentException("unsupported replay capability contract version");
    }
    externalEffectCategories = List.copyOf(externalEffectCategories);
  }

  /** 显式保留未知声明，防止调用方把缺少元数据误解为安全。 */
  public static ReplayCapabilityDeclaration unknown(String pluginId) {
    return new ReplayCapabilityDeclaration(
        pluginId,
        UNKNOWN,
        List.of(UNKNOWN),
        IdempotencyCapability.UNKNOWN,
        UNKNOWN,
        UNKNOWN,
        CURRENT_CONTRACT_VERSION);
  }

  public boolean isFullyDeclared() {
    return !UNKNOWN.equals(pluginVersion)
        && !externalEffectCategories.contains(UNKNOWN)
        && idempotencyCapability != IdempotencyCapability.UNKNOWN
        && !UNKNOWN.equals(idempotencyKeyScope)
        && !UNKNOWN.equals(recoveryProtocolId);
  }

  /**
   * 判断保守冻结声明是否覆盖实际
   * 执行配置解析出的能力。
   */
  public boolean covers(ReplayCapabilityDeclaration actual) {
    return isFullyDeclared()
        && idempotencyCapability != IdempotencyCapability.NONE
        && EmptyChecks.isNotNull(actual)
        && actual.isFullyDeclared()
        && actual.idempotencyCapability() != IdempotencyCapability.NONE
        && pluginId.equals(actual.pluginId())
        && pluginVersion.equals(actual.pluginVersion())
        && externalEffectCategories.containsAll(actual.externalEffectCategories())
        && idempotencyCapability == actual.idempotencyCapability()
        && idempotencyKeyScope.equals(actual.idempotencyKeyScope())
        && recoveryProtocolId.equals(actual.recoveryProtocolId())
        && contractVersion == actual.contractVersion();
  }
}
