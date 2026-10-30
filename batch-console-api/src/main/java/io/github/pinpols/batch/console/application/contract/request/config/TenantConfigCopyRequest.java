package io.github.pinpols.batch.console.application.contract.request.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Data;

/**
 * 跨租户配置复制请求。
 *
 * <p>从源租户读取配置，推送到目标租户列表。
 */
@Data
public class TenantConfigCopyRequest {

  /** 源租户 ID。 */
  @NotBlank(message = "sourceTenantId must not be blank")
  @Size(max = 64)
  private String sourceTenantId;

  /** 目标租户 ID 列表（最多50个）；复制服务拒绝将源租户列为目标，防止回写环境配置。 */
  @NotEmpty(message = "targetTenantIds must not be empty")
  @Size(max = 50, message = "targetTenantIds must not exceed 50")
  private List<@NotBlank @Size(min = 1, max = 64) String> targetTenantIds;

  /** 要复制的配置类型。为空则复制全部支持的配置类型。 */
  private Set<@NotNull ConfigType> configTypes;

  /** 非空时按 jobCode 构建最小依赖配置包，而不是复制整类配置。 */
  @Size(max = 100, message = "jobCodes must not exceed 100")
  private List<@NotBlank @Size(min = 1, max = 128) String> jobCodes;

  /** 初始化模式：SKIP_EXISTING 或 UPSERT。默认 SKIP_EXISTING。 */
  private TenantConfigBatchInitRequest.InitMode mode =
      TenantConfigBatchInitRequest.InitMode.SKIP_EXISTING;

  /** 试运行模式。 */
  private boolean dryRun;

  public enum ConfigType {
    JOB_DEFINITION,
    WORKFLOW_DEFINITION,
    FILE_CHANNEL,
    FILE_TEMPLATE,
    RESOURCE_QUEUE,
    BATCH_WINDOW,
    BUSINESS_CALENDAR,
    QUOTA_POLICY,
    ALERT_ROUTING;

    // 兼容前端简写："JOB" → JOB_DEFINITION, "WORKFLOW" → WORKFLOW_DEFINITION 等
    private static final Map<String, ConfigType> ALIAS_MAP = Stream.of(values())
        .collect(Collectors.toMap(
            v -> v.name().replace("_DEFINITION", ""), Function.identity(), (a, b) -> a));

    @JsonCreator
    public static ConfigType fromValue(String value) {
      if (value == null) {
        return null;
      }
      String trimmedValue = value.trim();
      // 配置类型是协议标识，大小写转换不应依赖部署主机的默认语言环境。
      String upper = trimmedValue.toUpperCase(Locale.ROOT);
      try {
        return ConfigType.valueOf(upper);
      } catch (IllegalArgumentException ignored) {
        ConfigType alias = ALIAS_MAP.get(upper);
        if (alias != null) {
          return alias;
        }
        throw new IllegalArgumentException("Unknown ConfigType: '"
            + value
            + "'. Accepted values: "
            + ALIAS_MAP.keySet()
            + " or full names like JOB_DEFINITION");
      }
    }
  }
}
